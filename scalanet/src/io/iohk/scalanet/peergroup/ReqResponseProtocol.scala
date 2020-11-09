package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID

import cats.implicits._
import cats.effect.{Fiber, Resource}
import cats.effect.concurrent.{Ref, Semaphore}
import io.iohk.scalanet.codec.FramingCodec
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.peergroup.implicits._
import io.iohk.scalanet.peergroup.Channel.{ChannelEvent, MessageReceived}
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.ChannelId
import io.iohk.scalanet.peergroup.ReqResponseProtocol._
import io.iohk.scalanet.peergroup.dynamictls.{DynamicTLSPeerGroup, Secp256k1}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.PeerInfo
import io.iohk.scalanet.peergroup.udp.DynamicUDPPeerGroup
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
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
  ): Task[ReqResponseChannel[A, M]] = {
    channelMapRef.get.map(_.get(channelId)).flatMap {
      case Some(channel) =>
        Task.pure(channel)

      case None =>
        channelSemaphore.withPermit {
          channelMapRef.get.map(_.get(channelId)).flatMap {
            case Some(channel) =>
              Task.pure(channel)

            case None =>
              group.client(to).allocated.flatMap {
                case (underlying, release) =>
                  val cleanup = release >> channelMapRef.update(_ - channelId)
                  // Keep in mind that stream is back pressured for all subscribers so in case of many parallel requests to one client
                  // waiting for response on first request can influence result of second request
                  val channel = new ReqResponseChannel(underlying, cleanup)
                  channelMapRef.update(_.updated(channelId, channel)).as(channel)
              }
          }
        }
    }
  }

  // It do not close the client channel after each message as in case of tcp it would be really costly
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
      c: ReqResponseChannel[A, M],
      messageToSend: MessageEnvelope[M],
      timeOutDuration: FiniteDuration
  ): Task[M] =
    for {
      // Subscribe first so we don't miss the response.
      subscription <- subscribeForResponse(c.sharedMessageObservable, messageToSend.id).start
      _ <- c.sendMessage(messageToSend)
      result <- subscription.join.map(_.m).timeout(timeOutDuration)
    } yield result

  private def subscribeForResponse(
      source: Observable[ChannelEvent[MessageEnvelope[M]]],
      responseId: UUID
  ): Task[MessageEnvelope[M]] = {
    source.collect {
      case MessageReceived(response) if response.id == responseId => response
    }.headL
  }

  /** Start handling requests in the background. */
  def startHandling(requestHandler: M => M): Task[Unit] = {
    group
      .nextServerEvent()
      .toObservable
      .collectChannelCreated
      .foreachL {
        case (channel, release) =>
          val channelId = (a.getAddress(processAddress), a.getAddress(channel.to))
          channel
            .nextMessage()
            .toIterant
            .collect {
              case MessageReceived(msg) => msg
            }
            .mapEval { msg =>
              channel.sendMessage(MessageEnvelope(msg.id, requestHandler(msg.m)))
            }
            .completedL
            .guarantee {
              // Release the channel and remove the background process from the map.
              release >> fiberMapRef.update(_ - channelId)
            }
            .start // Start running it in a background fiber.
            .flatMap { fiber =>
              // Remember we're running this so we can cancel when released.
              fiberMapRef.update(_.updated(channelId, fiber))
            }
            .runAsyncAndForget
      }
  }

  /** Stop background fibers. */
  private def cancelHandling(): Task[Unit] =
    fiberMapRef.get.flatMap { fiberMap =>
      fiberMap.values.toList.traverse(_.cancel.attempt)
    }.void >> fiberMapRef.set(Map.empty)

  /** Release all open channels */
  private def closeChannels(): Task[Unit] =
    channelMapRef.get.flatMap { channelMap =>
      channelMap.values.toList.traverse {
        _.release.attempt
      }.void
    }

  def processAddress: A = group.processAddress
}

object ReqResponseProtocol {
  class ReqResponseChannel[A, M](
      channel: Channel[A, MessageEnvelope[M]],
      val release: Release
  )(implicit scheduler: Scheduler) {

    // There will be one channel shared by all `send` methods which
    // subscribe to their responses. To avoid message stealing, this
    // observable replicates messages to each subscriber, however it
    // also consumes messages if there are no subscribers at all.
    val sharedMessageObservable: Observable[ChannelEvent[MessageEnvelope[M]]] =
      channel.messageObservable.share

    // This makes sure there's always at least one subscriber and
    // messages aren't queueing up (if there aren't any subscribers
    // then `.share` unsubscribes from the source).
    // An alternative solution would be to use `channel.next().toIterant.toChannel`
    // which creates a `monix.catnap.ConcurrentChannel` that broadcasts to everyone
    // who called `consume` on it.
    sharedMessageObservable.foreach(_ => ())

    def sendMessage(message: MessageEnvelope[M]): Task[Unit] =
      channel.sendMessage(message)
  }

  type ChannelMap[A, M] = Map[ChannelId, ReqResponseChannel[A, M]]

  final case class MessageEnvelope[M](id: UUID, m: M)
  object MessageEnvelope {

    /** scodec specific framing codec for streaming. */
    def framingCodec[M: Codec]: FramingCodec[MessageEnvelope[M]] = {
      new FramingCodec(defaultCodec[M])
    }

    /** scodec scpecific codec for a single message. */
    def defaultCodec[M: Codec]: Codec[MessageEnvelope[M]] = {
      import scodec.codecs.implicits._
      // Default scodec product codec deriviation due to implicits
      implicitly[Codec[MessageEnvelope[M]]]
    }
  }

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
        } yield protocol
      ) { protocol =>
        protocol.cancelHandling() >>
          protocol.closeChannels()
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
    implicit lazy val framingCodec = MessageEnvelope.framingCodec[M]
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
    implicit val codec = MessageEnvelope.defaultCodec[M]
    for {
      pg <- DynamicUDPPeerGroup[MessageEnvelope[M]](DynamicUDPPeerGroup.Config(address))
      prot <- buildProtocol(pg)
    } yield prot
  }

}
