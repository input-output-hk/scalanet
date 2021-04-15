package io.iohk.scalanet.discovery.ethereum.v4

import cats.implicits._
import cats.effect.Clock
import cats.effect.concurrent.Deferred
import com.typesafe.scalalogging.LazyLogging
import io.iohk.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg}
import io.iohk.scalanet.discovery.ethereum.Node
import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.peergroup.implicits.NextOps
import io.iohk.scalanet.peergroup.{Addressable, Channel, PeerGroup}
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.Channel.{ChannelIdle, DecodingError, MessageReceived, UnexpectedError}

import java.util.concurrent.TimeoutException
import java.net.InetAddress
import monix.eval.Task
import monix.tail.Iterant
import monix.catnap.CancelableF

import scala.concurrent.duration._
import scodec.{Attempt, Codec}

import scala.util.control.{NoStackTrace, NonFatal}
import scodec.bits.BitVector
import io.iohk.scalanet.discovery.ethereum.v4.Payload.Neighbors

import java.net.InetSocketAddress
import io.iohk.scalanet.discovery.hash.Keccak256

/** Present a stateless facade implementing the RPC methods
  * that correspond to the discovery protocol messages on top
  * of the peer group representing the other nodes.
  */
trait DiscoveryNetwork[A] extends DiscoveryRPC[DiscoveryNetwork.Peer[A]] {

  /** Start handling incoming requests using the local RPC interface.
    * The remote side is identified by its ID and address.*/
  def startHandling(handler: DiscoveryRPC[DiscoveryNetwork.Peer[A]]): Task[CancelableF[Task]]
}

object DiscoveryNetwork {

  /** The pair of node ID and the UDP socket where it can be contacted or where it contacted us from.
    * We have to use the pair for addressing a peer as well to set an expectation of the identity we
    * expect to talk to, i.e. who should sign the packets.
    */
  case class Peer[A](id: Node.Id, address: A) {
    override def toString: String =
      s"Peer(id = ${id.toHex}, address = $address)"

    lazy val kademliaId: Hash = Node.kademliaId(id)
  }
  object Peer {
    implicit def addressable[A: Addressable]: Addressable[Peer[A]] = new Addressable[Peer[A]] {
      override def getAddress(a: Peer[A]): InetSocketAddress =
        Addressable[A].getAddress(a.address)
    }
  }

  // Errors that stop the processing of incoming messages on a channel.
  class PacketException(message: String) extends Exception(message) with NoStackTrace

  def apply[A](
      peerGroup: PeerGroup[A, Packet],
      privateKey: PrivateKey,
      // Sent in pings; some clients use the the TCP port in the `from` so it should be accurate.
      localNodeAddress: Node.Address,
      toNodeAddress: A => Node.Address,
      config: DiscoveryConfig
  )(implicit codec: Codec[Payload], sigalg: SigAlg, clock: Clock[Task]): Task[DiscoveryNetwork[A]] = Task {
    new DiscoveryNetwork[A] with LazyLogging {

      import DiscoveryRPC.ENRSeq
      import Payload._

      private val expirationSeconds = config.messageExpiration.toSeconds
      private val maxClockDriftSeconds = config.maxClockDrift.toSeconds
      private val currentTimeSeconds = clock.realTime(SECONDS)

      private val maxNeighborsPerPacket = getMaxNeighborsPerPacket

      /** Start a fiber that accepts incoming channels and starts a dedicated fiber
        * to handle every channel separtely, processing their messages one by one.
        * This is fair: every remote connection can be throttled independently
        * of each other, as well as based on operation type by the `handler` itself.
        */
      override def startHandling(handler: DiscoveryRPC[Peer[A]]): Task[CancelableF[Task]] =
        for {
          cancelToken <- Deferred[Task, Unit]
          _ <- peerGroup.nextServerEvent
            .withCancelToken(cancelToken)
            .toIterant
            .mapEval {
              case ChannelCreated(channel, release) =>
                handleChannel(handler, channel, cancelToken)
                  .guarantee(release)
                  .onErrorRecover {
                    case ex: TimeoutException =>
                    case NonFatal(ex) =>
                      logger.error(s"Error handling channel from ${channel.to}: $ex")
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
          handler: DiscoveryRPC[Peer[A]],
          channel: Channel[A, Packet],
          cancelToken: Deferred[Task, Unit]
      ): Task[Unit] = {
        channel.nextChannelEvent
          .withCancelToken(cancelToken)
          .timeout(config.messageExpiration) // Messages older than this would be ignored anyway.
          .toIterant
          .mapEval {
            case MessageReceived(packet) =>
              currentTimeSeconds.flatMap { timestamp =>
                Packet.unpack(packet) match {
                  case Attempt.Successful((payload, remotePublicKey)) =>
                    payload match {
                      case _: Payload.Response =>
                        // Not relevant on the server channel.
                        Task.unit

                      case p: Payload.HasExpiration[_] if isExpired(p, timestamp) =>
                        Task(logger.debug(s"Ignoring expired request from ${channel.to}; ${p.expiration} < $timestamp"))

                      case p: Payload.Request =>
                        handleRequest(handler, channel, remotePublicKey, packet.hash, p)
                    }

                  case Attempt.Failure(err) =>
                    Task(logger.debug(s"Failed to unpack packet: $err; ${packet.show}")) >>
                      Task.raiseError(new PacketException(s"Failed to unpack message: $err"))
                }
              }

            case DecodingError =>
              Task.raiseError(new PacketException("Failed to decode a message."))

            case UnexpectedError(ex) =>
              Task.raiseError(new PacketException(ex.getMessage))

            case ChannelIdle(_, _) =>
              // we do not use idle peer detection in discovery
              Task.unit
          }
          .completedL
      }

      private def handleRequest(
          handler: DiscoveryRPC[Peer[A]],
          channel: Channel[A, Packet],
          remotePublicKey: PublicKey,
          hash: Hash,
          payload: Payload.Request
      ): Task[Unit] = {
        val caller = Peer(remotePublicKey, channel.to)

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
                .take(config.kademliaBucketSize) // NOTE: Other nodes could use a different setting.
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
        maybeResponse
          .onErrorRecoverWith {
            case NonFatal(ex) =>
              // Not responding to this one, but it shouldn't stop handling further requests.
              Task(logger.error(s"Error handling incoming request: $ex")).as(None)
          }
          .flatMap(_.fold(Task.unit)(f))

      /** Serialize the payload to binary and sign the packet. */
      private def pack(payload: Payload): Task[Packet] =
        Packet
          .pack(payload, privateKey)
          .fold(
            err => Task.raiseError(new IllegalArgumentException(s"Could not pack $payload: $err")),
            packet => Task.pure(packet)
          )

      /** Set a future expiration time on the payload. */
      private def setExpiration(payload: Payload): Task[Payload] = {
        payload match {
          case p: Payload.HasExpiration[_] =>
            currentTimeSeconds.map(t => p.withExpiration(t + expirationSeconds))
          case p =>
            Task.pure(p)
        }
      }

      /** Check whether an incoming packet is expired. According to the spec anyting with
        * an absolute expiration timestamp in the past is expired, however it's a known
        * issue that clock drift among nodes leads to dropped messages. Therefore we have
        * the option to set an acceptable leeway period as well.
        *
        * For example if another node sets the expiration of its message 1 minute in the future,
        * but our clock is 90 seconds ahead of time, we already see it as expired. Setting
        * our expiration time to 1 hour wouldn't help in this case.
        */
      private def isExpired(payload: HasExpiration[_], now: Long): Boolean =
        payload.expiration < now - maxClockDriftSeconds

      /** Ping a peer. */
      override val ping = (peer: Peer[A]) =>
        (localEnrSeq: Option[ENRSeq]) =>
          peerGroup.client(peer.address).use { channel =>
            channel
              .send(
                Ping(version = 4, from = localNodeAddress, to = toNodeAddress(peer.address), 0, localEnrSeq)
              )
              .flatMap { packet =>
                // Workaround for 1.10 Parity nodes that send back the hash of the Ping data
                // rather than the hash of the whole packet (over signature + data).
                // https://github.com/paritytech/parity/issues/8038
                // https://github.com/ethereumproject/go-ethereum/issues/312
                val dataHash = Keccak256(packet.data)

                channel.collectFirstResponse(peer.id) {
                  case Pong(_, pingHash, _, maybeRemoteEnrSeq) if pingHash == packet.hash || pingHash == dataHash =>
                    maybeRemoteEnrSeq
                }
              }
          }

      /** Ask a peer about neighbors of a target.
        *
        * NOTE: There can be many responses to a request due to the size limits of packets.
        * The responses cannot be tied to the request, so if we do multiple requests concurrently
        * we might end up mixing the results. One option to remedy would be to make sure we
        * only send one request to a given node at any time, waiting with the next until all
        * responses are collected, which can be 16 nodes or 7 seconds, whichever comes first.
        * However that would serialize all requests, might result in some of them taking much
        * longer than expected.
        */
      override val findNode = (peer: Peer[A]) =>
        (target: PublicKey) =>
          peerGroup.client(peer.address).use { channel =>
            channel.send(FindNode(target, 0)).flatMap { _ =>
              channel.collectAndFoldResponses(peer.id, config.kademliaTimeout, Vector.empty[Node]) {
                case Neighbors(nodes, _) => nodes
              } { (acc, nodes) =>
                val found = (acc ++ nodes).take(config.kademliaBucketSize)
                if (found.size < config.kademliaBucketSize) Left(found) else Right(found)
              }
            }
          }

      /** Fetch the ENR of a peer. */
      override val enrRequest = (peer: Peer[A]) =>
        (_: Unit) =>
          peerGroup.client(peer.address).use { channel =>
            channel
              .send(ENRRequest(0))
              .flatMap { packet =>
                channel.collectFirstResponse(peer.id) {
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
            _ <- Task(
              logger
                .debug(s"Sending ${payload.getClass.getSimpleName} from ${peerGroup.processAddress} to ${channel.to}")
            )
            _ <- channel.sendMessage(packet)
          } yield packet
        }

        /** Collect responses that match a partial function or raise a timeout exception. */
        def collectResponses[T](
            // The ID of the peer we expect the responses to be signed by.
            publicKey: PublicKey,
            // The absolute end we are willing to wait for the correct message to arrive.
            deadline: Deadline
        )(pf: PartialFunction[Payload.Response, T]): Iterant[Task, T] =
          channel.nextChannelEvent
            .timeoutL(Task(config.requestTimeout.min(deadline.timeLeft)))
            .toIterant
            .collect {
              case MessageReceived(packet) => packet
            }
            .mapEval { packet =>
              currentTimeSeconds.flatMap { timestamp =>
                Packet.unpack(packet) match {
                  case Attempt.Successful((payload, remotePublicKey)) =>
                    payload match {
                      case _ if remotePublicKey != publicKey =>
                        Task.raiseError(new PacketException("Remote public key did not match the expected peer ID."))

                      case _: Payload.Request =>
                        // Not relevant on the client channel.
                        Task.pure(None)

                      case p: Payload.HasExpiration[_] if isExpired(p, timestamp) =>
                        Task(
                          logger.debug(s"Ignoring expired response from ${channel.to}; ${p.expiration} < $timestamp")
                        ).as(None)

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
        def collectFirstResponse[T](publicKey: PublicKey)(pf: PartialFunction[Payload.Response, T]): Task[Option[T]] =
          channel
            .collectResponses(publicKey: PublicKey, config.requestTimeout.fromNow)(pf)
            .headOptionL
            .onErrorRecoverWith {
              case NonFatal(ex) =>
                Task(logger.debug(s"Failed to collect response from ${channel.to}: ${ex.getMessage}")).as(None)
            }

        /** Collect responses that match the partial function and fold them while the folder function returns Left.  */
        def collectAndFoldResponses[T, Z](publicKey: PublicKey, timeout: FiniteDuration, seed: Z)(
            pf: PartialFunction[Payload.Response, T]
        )(
            f: (Z, T) => Either[Z, Z]
        ): Task[Option[Z]] =
          channel
            .collectResponses(publicKey, timeout.fromNow)(pf)
            .attempt
            .foldWhileLeftEvalL(Task.pure((seed -> 0).some)) {
              case (Some((acc, count)), Left(ex: TimeoutException)) if count > 0 =>
                // We have a timeout but we already accumulated some results, so return those.
                Task.pure(Right(Some((acc, count))))

              case (_, Left(ex)) =>
                // Unexpected error, discard results, if any.
                Task(logger.debug(s"Failed to fold responses from ${channel.to}: ${ex.getMessage}")).as(Right(None))

              case (Some((acc, count)), Right(response)) =>
                // New response, fold it with the existing to decide if we need more.
                val next = (acc: Z) => Some(acc -> (count + 1))
                Task.pure(f(acc, response).bimap(next, next))

              case (None, _) =>
                // Invalid state - this cannot happen
                Task.raiseError(
                  new IllegalStateException(s"Unexpected state while collecting responses from ${channel.to}")
                )
            }
            .map(_.map(_._1))

      }
    }
  }

  /** Estimate how many neihbors we can fit in the maximum protol message size. */
  def getMaxNeighborsPerPacket(implicit codec: Codec[Payload], sigalg: SigAlg): Int = {
    val sampleNode = Node(
      id = PublicKey(BitVector(Array.fill[Byte](sigalg.PublicKeyBytesSize)(0xff.toByte))),
      address = Node.Address(
        ip = InetAddress.getByName("::1"), // IPv6, longer than IPv4,
        udpPort = 40000,
        tcpPort = 50000
      )
    )
    val expiration = System.currentTimeMillis

    Iterator
      .iterate(List(sampleNode))(sampleNode :: _)
      .map { nodes =>
        val payload = Neighbors(nodes, expiration)
        // Take a shortcut here so we don't need a valid private key and sign all incremental messages.
        val dataBitsSize = codec.encode(payload).require.size
        val packetSize = Packet.MacBitsSize + Packet.SigBitsSize + dataBitsSize
        packetSize
      }
      .takeWhile(_ <= Packet.MaxPacketBitsSize)
      .length
  }
}
