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
import io.iohk.scalanet.peergroup.Channel.{MessageReceived, DecodingError, UnexpectedError}
import java.util.concurrent.TimeoutException
import monix.eval.Task
import monix.tail.Iterant
import monix.catnap.CancelableF
import scala.concurrent.duration._
import scodec.{Codec, Attempt}
import scala.util.control.NonFatal
import scodec.bits.BitVector
import io.iohk.scalanet.discovery.ethereum.v4.Payload.Neighbors

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
      import Payload._

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
          .mapEval {
            case MessageReceived(packet) =>
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

            case DecodingError =>
              Task.raiseError(new IllegalArgumentException("Failed to decode a message."))

            case UnexpectedError(ex) =>
              Task.raiseError(ex)
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
                  channel.send(Neighbors(group.toList, 0))
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
                Ping(version = 4, from = localAddress, to = toAddress(to), 0, localEnrSeq)
              )
              .flatMap { packet =>
                channel.collectFirstResponse {
                  case Pong(_, pingHash, _, maybeRemoteEnrSeq) if pingHash == packet.hash =>
                    maybeRemoteEnrSeq
                }
              }
          }

      override val findNode = (to: A) =>
        (target: PublicKey) =>
          peerGroup.client(to).use { channel =>
            channel.send(FindNode(target, 0)).flatMap { _ =>
              channel.collectAndFoldResponses(kademliaTimeout, Vector.empty[Node]) {
                case Neighbors(nodes, _) => nodes
              } { (acc, nodes) =>
                val found = (acc ++ nodes).take(kademliaBucketSize)
                if (found.size < kademliaBucketSize) Left(found) else Right(found)
              }
            }
          }

      override val enrRequest = (to: A) =>
        (_: Unit) =>
          peerGroup.client(to).use { channel =>
            channel
              .send(ENRRequest(0))
              .flatMap { packet =>
                channel.collectFirstResponse {
                  case ENRResponse(requestHash, enr) if requestHash == packet.hash =>
                    enr
                }
              }
          }

      private implicit class ChannelOps(channel: Channel[A, Packet]) {

        /** Set the expiration, pack and send the data.
          * Return the packet so we can use the hash for expected responses.
          */
        def send(payload: Payload): Task[Packet] = {
          for {
            expiring <- setExpiration(payload)
            packet <- pack(expiring)
            _ <- channel.sendMessage(packet)
          } yield packet
        }

        /** Collect responses that match a partial function or raise a timeout exception. */
        def collectResponses[T](
            // The absolute end we are willing to wait for the correct message to arrive.
            deadline: Deadline
        )(pf: PartialFunction[Payload.Response, T]): Iterant[Task, T] =
          channel
            .nextMessage()
            .timeoutL(Task(requestTimeout.min(deadline.timeLeft)))
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

        /** Collect the first response that matches the partial function or return None if one cannot be found */
        def collectFirstResponse[T](pf: PartialFunction[Payload.Response, T]): Task[Option[T]] =
          channel
            .collectResponses(requestTimeout.fromNow)(pf)
            .headOptionL
            .onErrorRecover {
              case NonFatal(ex) => None
            }

        /** Collect responses that match the partial function and fold them while the folder function returns Left.  */
        def collectAndFoldResponses[T, Z](timeout: FiniteDuration, seed: Z)(pf: PartialFunction[Payload.Response, T])(
            f: (Z, T) => Either[Z, Z]
        ): Task[Option[Z]] =
          channel
            .collectResponses(timeout.fromNow)(pf)
            .attempt
            .foldWhileLeftL((seed -> 0).some) {
              case (Some((acc, count)), Left(ex: TimeoutException)) if count > 0 =>
                // We have a timeout but we already accumulated some results, so return those.
                Right(Some((acc, count)))

              case (_, Left(_)) =>
                // Unexpected error, discard results, if any.
                Right(None)

              case (Some((acc, count)), Right(response)) =>
                // New response, fold it with the existing to decide if we need more.
                val next = (acc: Z) => Some(acc -> (count + 1))
                f(acc, response).bimap(next, next)
            }
            .map(_.map(_._1))

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

  def getMaxNeighborsPerPacket(implicit codec: Codec[Payload], sigalg: SigAlg): Int = {
    val sampleNode = Node(
      id = PublicKey(BitVector(Array.fill[Byte](sigalg.PublicKeyBytesSize)(0xff.toByte))),
      address = Node.Address(
        ip = BitVector(Array.fill[Byte](4)(0xff.toByte)),
        udpPort = 40000,
        tcpPort = 50000
      )
    )
    val expiration = System.currentTimeMillis

    Stream
      .iterate(List(sampleNode))(sampleNode :: _)
      .map { nodes =>
        val payload = Neighbors(nodes, expiration)
        // Take a shortcut here so we don't need a valid private key and sign all incremental messages.
        val dataBitsSize = codec.encode(payload).require.size
        Packet.MacBitsSize + Packet.SigBitsSize + dataBitsSize
      }
      .takeWhile(_ <= Packet.MaxPacketBitsSize)
      .length
  }
}
