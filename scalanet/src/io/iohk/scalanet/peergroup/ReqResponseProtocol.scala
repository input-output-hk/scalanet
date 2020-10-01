package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID

import cats.implicits._
import cats.effect.{Fiber, Resource}
import cats.effect.concurrent.{Ref, Semaphore}
import io.iohk.scalanet.codec.FramingCodec
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.peergroup.Channel.{ChannelEvent, MessageReceived}
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.ChannelId
import io.iohk.scalanet.peergroup.ReqResponseProtocol._
import io.iohk.scalanet.peergroup.dynamictls.{DynamicTLSPeerGroup, Secp256k1}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.PeerInfo
import io.iohk.scalanet.peergroup.udp.DynamicUDPPeerGroup
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.observables.ConnectableObservable
import scodec.Codec

import scala.concurrent.duration.{FiniteDuration, _}

/**
  * Simple higher level protocol on top of generic peer group. User is shielded from differnt implementation details like:
  * channels, observables etc.
  *
  * For now used only in testing as:
  *   - it lacks any error handling
  *   - it is not entairly thread safe
  *   - can only handle simple server handler
  *   - there is no resource cleaning
  *
  * @param group transport peer group
  * @param state currently open client channels
  * @tparam A used addressing scheme
  * @tparam M the message type.
  */
class ReqResponseProtocol[A, M](
    group: PeerGroup[A, MessageEnvelope[M]],
    channelSemaphore: Semaphore[Task],
    channelMapRef: Ref[Task, ReqResponseProtocol.ChannelMap[A, M]],
    fiberMapRef: Ref[Task, Map[ChannelId, Fiber[Task, Unit]]]
)(implicit s: Scheduler, a: Addressable[A]) {

  private def getChan(
      to: A,
      channelId: ChannelId
  ): Task[Channel[A, MessageEnvelope[M]]] = {
    channelMapRef.get.map(_.get(channelId)).flatMap {
      case Some((channel, _)) =>
        Task.pure(channel)

      case None =>
        channelSemaphore.withPermit {
          channelMapRef.get.map(_.get(channelId)).flatMap {
            case Some((channel, _)) =>
              Task.pure(channel)
            case None =>
              group.client(to).allocated.flatMap {
                case (channel, release) =>
                  val cleanup = release >> channelMapRef.update(_ - channelId)
                  // Keep in mind that stream is back pressured for all subscribers so in case of many parallel requests to one client
                  // waiting for response on first request can influence result of second request
                  for {
                    _ <- channelMapRef.update(_.updated(channelId, channel -> cleanup))
                    // start publishing incoming messages to any subscriber
                    // in normal circumstances we should keep cancellation token to clear up resources
                    _ = channel.in.connect()
                  } yield channel
              }
          }
        }
    }
  }

  // It do not closes client channel after each message as, in case of tcp it would be really costly
  // to create new tcp connection for each message.
  // it probably should return Task[Either[E, M]]
  def send(m: M, to: A, requestDuration: FiniteDuration = 5.seconds): Task[M] = {
    val channelId = (a.getAddress(processAddress), a.getAddress(to))
    for {
      ch <- getChan(to, channelId)
      randomUuid = UUID.randomUUID()
      mes = MessageEnvelope(randomUuid, m)
      resp <- sendMandAwaitForResponse(ch, mes, requestDuration)
    } yield resp
  }

  private def sendMandAwaitForResponse(
      c: Channel[A, MessageEnvelope[M]],
      messageToSend: MessageEnvelope[M],
      timeOutDuration: FiniteDuration
  ): Task[M] =
    for {
      // sending and subsription are done in parallel to not miss response message by chance
      // alse in case of send failure, any resource will be cleaned
      result <- Task
        .parMap2(c.sendMessage(messageToSend), subscribeForResponse(c.in, messageToSend.id))(
          (_, response) => response.m
        )
        .timeout(timeOutDuration)
    } yield result

  private def subscribeForResponse(
      source: ConnectableObservable[ChannelEvent[MessageEnvelope[M]]],
      responseId: UUID
  ): Task[MessageEnvelope[M]] = {
    source.collect {
      case MessageReceived(response) if response.id == responseId => response
    }.headL
  }

  def startHandling(requestHandler: M => M): Task[Unit] = {
    group.server.refCount.collectChannelCreated
      .foreachL {
        case (channel, release) =>
          val channelId = (a.getAddress(processAddress), a.getAddress(channel.to))
          channel.in.refCount
            .collect {
              case MessageReceived(msg) => msg
            }
            .mapEval { msg =>
              channel.sendMessage(MessageEnvelope(msg.id, requestHandler(msg.m)))
            }
            .guarantee {
              release >> fiberMapRef.update(_ - channelId)
            }
            .foreachL(_ => ())
            .start
            .flatMap { fiber =>
              fiberMapRef.update(_.updated(channelId, fiber))
            }
            .runAsyncAndForget
      }
  }

  def processAddress: A = group.processAddress
}

object ReqResponseProtocol {
  import scodec.codecs.implicits._

  // ChannelMap contains channels created with their release method.
  type ChannelMap[A, M] = Map[ChannelId, (Channel[A, MessageEnvelope[M]], Release)]

  // Default scodec product codec deriviation due to implicits
  final case class MessageEnvelope[M](id: UUID, m: M)

  private def buildProtocol[A, M](
      group: PeerGroup[A, MessageEnvelope[M]]
  )(implicit s: Scheduler, a: Addressable[A]): Resource[Task, ReqResponseProtocol[A, M]] = {
    Resource
      .make(
        for {
          channelSemaphore <- Semaphore[Task](1)
          channelMapRef <- Ref.of[Task, ChannelMap[A, M]](Map.empty)
          fiberMapRef <- Ref.of[Task, Map[ChannelId, Fiber[Task, Unit]]](Map.empty)
          protocol = new ReqResponseProtocol[A, M](group, channelSemaphore, channelMapRef, fiberMapRef)
        } yield (protocol, channelMapRef, fiberMapRef)
      ) {
        case (_, channelMapRef, fiberMapRef) =>
          fiberMapRef.get.flatMap { fiberMap =>
            fiberMap.values.toList.traverse(_.cancel)
          } >>
            channelMapRef.get.flatMap { channelMap =>
              channelMap.values.toList.traverse {
                case (_, release) => release
              }.void
            }
      }
      .map {
        case (protocol, _, _) => protocol
      }
  }

  sealed abstract class TransportProtocol extends Product with Serializable {
    type AddressingType
    def getProtocol[M](
        address: InetSocketAddress
    )(implicit s: Scheduler, c: Codec[M]): Resource[Task, ReqResponseProtocol[AddressingType, M]]
  }
  case object DynamicUDP extends TransportProtocol {
    override type AddressingType = InetMultiAddress

    override def getProtocol[M](
        address: InetSocketAddress
    )(implicit s: Scheduler, c: Codec[M]): Resource[Task, ReqResponseProtocol[InetMultiAddress, M]] = {
      getDynamicUdpReqResponseProtocolClient(address)
    }
  }

  case object DynamicTLS extends TransportProtocol {
    override type AddressingType = PeerInfo

    override def getProtocol[M](
        address: InetSocketAddress
    )(implicit s: Scheduler, c: Codec[M]): Resource[Task, ReqResponseProtocol[PeerInfo, M]] = {
      getTlsReqResponseProtocolClient(address)
    }
  }

  def getTlsReqResponseProtocolClient[M](
      address: InetSocketAddress
  )(implicit s: Scheduler, c: Codec[M]): Resource[Task, ReqResponseProtocol[PeerInfo, M]] = {
    val codec = implicitly[Codec[MessageEnvelope[M]]]
    implicit lazy val framingCodec = new FramingCodec[MessageEnvelope[M]](codec)
    val rnd = new SecureRandom()
    val hostkeyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)

    for {
      config <- Resource.liftF(Task.fromTry(DynamicTLSPeerGroup.Config(address, Secp256k1, hostkeyPair, rnd)))
      pg <- DynamicTLSPeerGroup[MessageEnvelope[M]](config)
      prot <- buildProtocol(pg)
    } yield prot
  }

  def getDynamicUdpReqResponseProtocolClient[M](
      address: InetSocketAddress
  )(implicit s: Scheduler, c: Codec[M]): Resource[Task, ReqResponseProtocol[InetMultiAddress, M]] = {
    for {
      pg <- DynamicUDPPeerGroup[MessageEnvelope[M]](DynamicUDPPeerGroup.Config(address))
      prot <- buildProtocol(pg)
    } yield prot
  }

}
