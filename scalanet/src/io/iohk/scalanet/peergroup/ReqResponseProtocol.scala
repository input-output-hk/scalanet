package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import cats.implicits._
import cats.effect.{Fiber, Resource}
import cats.effect.concurrent.{Ref, Semaphore}
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.peergroup.implicits._
import io.iohk.scalanet.peergroup.Channel.{ChannelEvent, MessageReceived}
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.ChannelId
import io.iohk.scalanet.peergroup.ReqResponseProtocol._
import io.iohk.scalanet.peergroup.dynamictls.{DynamicTLSPeerGroup, Secp256k1}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.{FramingConfig, PeerInfo}
import io.iohk.scalanet.peergroup.udp.DynamicUDPPeerGroup
import monix.eval.Task
import monix.execution.Scheduler
import monix.tail.Iterant
import scodec.Codec

import scala.concurrent.duration.{FiniteDuration, _}
import monix.catnap.ConcurrentChannel

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
                  ReqResponseChannel(underlying, cleanup).flatMap { channel =>
                    channelMapRef.update(_.updated(channelId, channel)).as(channel)
                  }
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
      subscription <- c.subscribeForResponse(messageToSend.id, timeOutDuration).start
      _ <- c.sendMessage(messageToSend).timeout(timeOutDuration)
      result <- subscription.join.map(_.m)
    } yield result

  /** Start handling requests in the background. */
  def startHandling(requestHandler: M => M): Task[Unit] = {
    group.nextServerEvent.toObservable.collectChannelCreated
      .foreachL {
        case (channel, release) =>
          val channelId = (a.getAddress(processAddress), a.getAddress(channel.to))
          channel.nextChannelEvent.toIterant
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
      concurrentChannel: ReqResponseChannel.ConChan[M],
      val release: Release
  ) {

    def sendMessage(message: MessageEnvelope[M]): Task[Unit] =
      channel.sendMessage(message)

    def subscribeForResponse(
        responseId: UUID,
        timeOutDuration: FiniteDuration
    ): Task[MessageEnvelope[M]] = {
      concurrentChannel.consume.use { consumer =>
        Iterant
          .repeatEvalF(consumer.pull)
          .flatMap[ChannelEvent[MessageEnvelope[M]]] {
            case Left(e) => Iterant.haltS(e)
            case Right(e) => Iterant.pure(e)
          }
          .collect {
            case MessageReceived(response) if response.id == responseId => response
          }
          .headOptionL
          .flatMap {
            case None =>
              Task.raiseError(new RuntimeException(s"Didn't receive a response for request $responseId"))
            case Some(response) =>
              Task.pure(response)
          }
          .timeout(timeOutDuration)
      }
    }
  }
  object ReqResponseChannel {
    // Sending a request subscribes to the common channel with a single underlying message queue,
    // expecting to see the response with the specific ID. To avoid message stealing, broadcast
    // messages to a concurrent channel, so every consumer gets every message.
    type ConChan[M] = ConcurrentChannel[Task, Option[Throwable], ChannelEvent[MessageEnvelope[M]]]

    def apply[A, M](channel: Channel[A, MessageEnvelope[M]], release: Task[Unit]): Task[ReqResponseChannel[A, M]] =
      for {
        concurrentChannel <- ConcurrentChannel.of[Task, Option[Throwable], ChannelEvent[MessageEnvelope[M]]]
        producer <- channel.nextChannelEvent.toIterant.pushToChannel(concurrentChannel).start
      } yield new ReqResponseChannel(channel, concurrentChannel, producer.cancel >> release)
  }

  type ChannelMap[A, M] = Map[ChannelId, ReqResponseChannel[A, M]]

  final case class MessageEnvelope[M](id: UUID, m: M)
  object MessageEnvelope {

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

  def getTlsReqResponseProtocolClient[M](framingConfig: FramingConfig)(
      address: InetSocketAddress
  )(implicit s: Scheduler, c: Codec[M]): Resource[Task, ReqResponseProtocol[PeerInfo, M]] = {
    implicit lazy val envelopeCodec = MessageEnvelope.defaultCodec[M]
    val rnd = new SecureRandom()
    val hostkeyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
    for {
      config <- Resource.liftF(
        Task.fromTry(
          DynamicTLSPeerGroup
            .Config(
              address,
              Secp256k1,
              hostkeyPair,
              rnd,
              useNativeTlsImplementation = false,
              framingConfig,
              maxIncomingMessageQueueSize = 100,
              None
            )
        )
      )
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
