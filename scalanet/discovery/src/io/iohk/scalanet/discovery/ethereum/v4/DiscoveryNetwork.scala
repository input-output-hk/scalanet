package io.iohk.scalanet.discovery.ethereum.v4

import cats.implicits._
import cats.effect.{Clock}
import com.typesafe.scalalogging.LazyLogging
import io.iohk.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg}
import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.peergroup.PeerGroup
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.Channel
import io.iohk.scalanet.peergroup.Channel.MessageReceived
import java.util.concurrent.TimeoutException
import monix.eval.Task
import monix.tail.Iterant
import monix.catnap.CancelableF
import monix.execution.cancelables.BooleanCancelable
import scala.concurrent.duration._
import scodec.{Codec, Attempt}
import scala.util.control.NonFatal

/** Present a stateless facade implementing the RPC methods
  * that correspond to the discovery protocol messages on top
  * of the peer group representing the other nodes.
  */
trait DiscoveryNetwork[A] extends DiscoveryRPC[A] {

  /** Start handling incoming requests using the local RPC interface.
    * The remote side is identified by its ID and address.*/
  def startHandling(handler: DiscoveryRPC[(PublicKey, A)]): Task[CancelableF[Task]]
}

object DiscoveryNetwork {

  def apply[A](
      peerGroup: PeerGroup[A, Packet],
      privateKey: PrivateKey,
      messageExpiration: FiniteDuration = 60.seconds,
      // Timeout for individual requests.
      requestTimeout: FiniteDuration = 3.seconds,
      // Timeout for collecting multiple potential Neighbors responses.
      kademliaTimeout: FiniteDuration = 7.seconds,
      // Max number of neighbours to expect.
      kademliaBucketSize: Int = 16
  )(implicit codec: Codec[Payload], sigalg: SigAlg, clock: Clock[Task]): Task[DiscoveryNetwork[A]] = Task {
    new DiscoveryNetwork[A] with LazyLogging {

      import DiscoveryRPC.ENRSeq

      private val expirationMillis = messageExpiration.toMillis

      private val currentTimeMillis = clock.monotonic(MILLISECONDS)

      private val maxNeighborsPerPacket = getMaxNeighborsPerPacket

      override def startHandling(handler: DiscoveryRPC[(PublicKey, A)]): Task[CancelableF[Task]] =
        for {
          cancelToken <- Task(BooleanCancelable())
          cancelable <- CancelableF[Task](Task(cancelToken.cancel()))
          _ <- peerGroup
            .nextServerEvent()
            .toIterant
            .takeWhile(_ => !cancelToken.isCanceled)
            .collect {
              case ChannelCreated(channel, release) => channel -> release
            }
            .mapEval {
              case (channel, release) =>
                handleChannel(handler, channel, cancelToken)
                  .guarantee(release)
                  .onErrorRecover {
                    case ex: TimeoutException =>
                    case NonFatal(ex) =>
                      logger.debug(s"Error on channel from ${channel.to}: $ex")
                  }
                  .startAndForget
            }
            .completedL
            .startAndForget
        } yield cancelable

      private def handleChannel(
          handler: DiscoveryRPC[(PublicKey, A)],
          channel: Channel[A, Packet],
          cancelToken: BooleanCancelable
      ): Task[Unit] = {
        channel
          .nextMessage()
          .timeout(messageExpiration) // Messages older than this would be ignored anyway.
          .toIterant
          .takeWhile(_ => !cancelToken.isCanceled)
          .collect {
            case MessageReceived(packet) => packet
          }
          .mapEval { packet =>
            currentTimeMillis.flatMap { timestamp =>
              Packet.unpack(packet) match {
                case Attempt.Successful((payload, remotePublicKey)) =>
                  payload match {
                    case _: Payload.Response =>
                      // Not relevant on the server channel.
                      Task.unit

                    case p: Payload.HasExpiration[_] if p.expiration < timestamp - expirationMillis =>
                      Task(logger.debug(s"Ignoring expired message from ${channel.to}"))

                    case p: Payload.Request =>
                      handleRequest(handler, channel, remotePublicKey, packet.hash, p)
                  }

                case Attempt.Failure(err) =>
                  Task.raiseError(
                    new IllegalArgumentException(s"Failed to unpack message: $err")
                  )
              }
            }
          }
          .completedL
      }

      private def handleRequest(
          handler: DiscoveryRPC[(PublicKey, A)],
          channel: Channel[A, Packet],
          remotePublicKey: PublicKey,
          hash: Hash,
          payload: Payload.Request
      ): Task[Unit] = {
        import Payload._

        val caller = (remotePublicKey, channel.to)

        payload match {
          case Ping(_, _, to, _, maybeRemoteEnrSeq) =>
            maybeRespond {
              handler.ping(caller)(maybeRemoteEnrSeq)
            } { maybeLocalEnrSeq =>
              send(channel, Pong(to, hash, 0, maybeLocalEnrSeq))
            }

          case FindNode(target, expiration) =>
            maybeRespond {
              handler.findNode(caller)(target)
            } { nodes =>
              nodes
                .grouped(maxNeighborsPerPacket)
                .toList
                .traverse { group =>
                  send(channel, Neighbors(group, 0))
                }
                .void
            }

          case ENRRequest(_) =>
            maybeRespond {
              handler.enrRequest(caller)(())
            } { enr =>
              send(channel, ENRResponse(hash, enr))
            }
        }
      }

      private def maybeRespond[Res](maybeResponse: Task[Option[Res]])(
          f: Res => Task[Unit]
      ): Task[Unit] =
        maybeResponse.flatMap {
          case Some(response) => f(response)
          case None => Task.unit
        }

      private def send(channel: Channel[A, Packet], payload: Payload): Task[Unit] = {
        for {
          expiring <- setExpiration(payload)
          packet <- pack(expiring)
          _ <- channel.sendMessage(packet)
        } yield ()
      }

      private def pack(payload: Payload): Task[Packet] =
        Packet
          .pack(payload, privateKey)
          .fold(
            err => Task.raiseError(new IllegalArgumentException(s"Could not pack $payload: $err")),
            packet => Task.pure(packet)
          )

      private def setExpiration(payload: Payload): Task[Payload] = {
        payload match {
          case p: Payload.HasExpiration[_] =>
            currentTimeMillis.map(t => p.withExpiration(t + expirationMillis))
          case p =>
            Task.pure(p)
        }
      }

      override val ping = (to: A) => (localEnrSeq: Option[ENRSeq]) => ???
      override val findNode = (to: A) => (target: PublicKey) => ???
      override val enrRequest = (to: A) => (_: Unit) => ???

    }
  }

  private implicit class NextOps[A](next: Task[Option[A]]) {
    def toIterant: Iterant[Task, A] =
      Iterant.repeatEvalF(next).takeWhile(_.isDefined).map(_.get)
  }

  def getMaxNeighborsPerPacket(implicit codec: Codec[Packet]) =
    // TODO: Iteratively expand the number of neighbors until we hit 1280 bits.
    4
}
