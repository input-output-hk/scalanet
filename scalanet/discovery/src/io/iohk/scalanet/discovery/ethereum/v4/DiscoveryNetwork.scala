package io.iohk.scalanet.discovery.ethereum.v4

import cats.implicits._
import cats.effect.{Clock}
import cats.effect.concurrent.Deferred
import com.typesafe.scalalogging.LazyLogging
import io.iohk.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg}
import io.iohk.scalanet.discovery.ethereum.Node
import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.peergroup.PeerGroup
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.Channel
import io.iohk.scalanet.peergroup.Channel.MessageReceived
import java.util.concurrent.TimeoutException
import monix.eval.Task
import monix.tail.Iterant
import monix.catnap.CancelableF
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
      kademliaBucketSize: Int = 16,
      toAddress: A => Node.Address
  )(implicit codec: Codec[Payload], sigalg: SigAlg, clock: Clock[Task]): Task[DiscoveryNetwork[A]] = Task {
    new DiscoveryNetwork[A] with LazyLogging {

      import DiscoveryRPC.ENRSeq

      private val expirationMillis = messageExpiration.toMillis

      private val currentTimeMillis = clock.monotonic(MILLISECONDS)

      private val maxNeighborsPerPacket = getMaxNeighborsPerPacket

      private val localAddress = toAddress(peerGroup.processAddress)

      /** Start a fiber that accepts incoming channels and starts a dedicated fiber
        * to handle every channel separtely, processing their messages one by one.
        * This is fair: every remote connection can be throttled independently
        * of each other, as well as based on operation type by the `handler` itself.
        */
      override def startHandling(handler: DiscoveryRPC[(PublicKey, A)]): Task[CancelableF[Task]] =
        for {
          cancelToken <- Deferred[Task, Unit]
          _ <- peerGroup
            .nextServerEvent()
            .withCancelToken(cancelToken)
            .toIterant
            .mapEval {
              case ChannelCreated(channel, release) =>
                channel -> release
                handleChannel(handler, channel, cancelToken)
                  .guarantee(release)
                  .onErrorRecover {
                    case ex: TimeoutException =>
                    case NonFatal(ex) =>
                      logger.debug(s"Error on channel from ${channel.to}: $ex")
                  }
                  .startAndForget
              case _ =>
                Task.unit
            }
            .completedL
            .startAndForget
          cancelable <- CancelableF[Task](cancelToken.complete(()))
        } yield cancelable

      private def handleChannel(
          handler: DiscoveryRPC[(PublicKey, A)],
          channel: Channel[A, Packet],
          cancelToken: Deferred[Task, Unit]
      ): Task[Unit] = {
        channel
          .nextMessage()
          .withCancelToken(cancelToken)
          .timeout(messageExpiration) // Messages older than this would be ignored anyway.
          .toIterant
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
              channel.send(Pong(to, hash, 0, maybeLocalEnrSeq)).void
            }

          case FindNode(target, expiration) =>
            maybeRespond {
              handler.findNode(caller)(target)
            } { nodes =>
              nodes
                .grouped(maxNeighborsPerPacket)
                .toList
                .traverse { group =>
                  channel.send(Neighbors(group, 0))
                }
                .void
            }

          case ENRRequest(_) =>
            maybeRespond {
              handler.enrRequest(caller)(())
            } { enr =>
              channel.send(ENRResponse(hash, enr)).void
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

      override val ping = (to: A) =>
        (localEnrSeq: Option[ENRSeq]) =>
          peerGroup.client(to).use { channel =>
            channel
              .send(
                Payload.Ping(version = 4, from = localAddress, to = toAddress(to), 0, localEnrSeq)
              )
              .flatMap { packet =>
                channel.collectFirstResponse {
                  case Payload.Pong(_, pingHash, _, maybeRemoteEnrSeq) if pingHash == packet.hash =>
                    maybeRemoteEnrSeq
                }
              }
          }

      override val findNode = (to: A) => (target: PublicKey) => ???

      override val enrRequest = (to: A) =>
        (_: Unit) =>
          peerGroup.client(to).use { channel =>
            channel
              .send(
                Payload.ENRRequest(0)
              )
              .flatMap { packet =>
                channel.collectFirstResponse {
                  case Payload.ENRResponse(requestHash, enr) if requestHash == packet.hash =>
                    enr
                }
              }
          }

      private implicit class ChannelOps(channel: Channel[A, Packet]) {
        def send(payload: Payload): Task[Packet] = {
          for {
            expiring <- setExpiration(payload)
            packet <- pack(expiring)
            _ <- channel.sendMessage(packet)
          } yield packet
        }

        def collectFirstResponse[T](pf: PartialFunction[Payload.Response, T]): Task[Option[T]] =
          channel
            .nextMessage()
            .timeout(requestTimeout)
            .toIterant
            .collect {
              case MessageReceived(packet) => packet
            }
            .mapEval { packet =>
              currentTimeMillis.flatMap { timestamp =>
                Packet.unpack(packet) match {
                  case Attempt.Successful((payload, remotePublicKey)) =>
                    payload match {
                      case _: Payload.Request =>
                        // Not relevant on the client channel.
                        Task.pure(None)

                      case p: Payload.HasExpiration[_] if p.expiration < timestamp - expirationMillis =>
                        Task.pure(None)

                      case p: Payload.Response =>
                        Task.pure(Some(p))
                    }

                  case Attempt.Failure(err) =>
                    Task.raiseError(
                      new IllegalArgumentException(s"Failed to unpack message: $err")
                    )
                }
              }
            }
            .collect {
              case Some(response) => response
            }
            .collect(pf)
            .headOptionL
      }

    }
  }

  private implicit class NextOps[A](next: Task[Option[A]]) {
    def toIterant: Iterant[Task, A] =
      Iterant.repeatEvalF(next).takeWhile(_.isDefined).map(_.get)

    def withCancelToken(token: Deferred[Task, Unit]): Task[Option[A]] =
      Task.race(token.get, next).map {
        case Left(()) => None
        case Right(x) => x
      }
  }

  def getMaxNeighborsPerPacket(implicit codec: Codec[Packet]) =
    // TODO: Iteratively expand the number of neighbors until we hit 1280 bits.
    4
}
