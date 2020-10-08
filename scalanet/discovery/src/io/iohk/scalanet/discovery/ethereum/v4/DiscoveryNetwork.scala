package io.iohk.scalanet.discovery.ethereum.v4

import cats.implicits._
import cats.effect.Clock
import com.typesafe.scalalogging.LazyLogging
import io.iohk.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg}
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.peergroup.PeerGroup
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.Channel
import io.iohk.scalanet.peergroup.Channel.MessageReceived
import java.util.concurrent.TimeoutException
import monix.eval.Task
import monix.reactive.Observable
import scala.concurrent.duration._
import scodec.{Codec, Attempt}
import scala.util.control.NonFatal

/** Present a stateless facade implementing the RPC methods
  * that correspond to the discovery protocol messages on top
  * of the peer group representing the other nodes.
  */
trait DiscoveryNetwork[A] {
  import DiscoveryNetwork.{CallIn, CallOut, Proc}

  /** A stream of incoming requests. */
  def requests: Observable[CallIn[A, _]]

  /** Sends a Ping request to the node, waits for the correct Pong response,
    * and returns the ENR sequence, if the Pong had one.
    */
  def ping: CallOut[A, Proc.Ping]

  /** Sends a FindNode request to the node and collects Neighbours responses
    * until a timeout or if the maximum expected number of nodes are returned.
    */
  def findNode: CallOut[A, Proc.FindNode]

  /** Sends an ENRRequest to the node and waits for the correct ENRResponse,
    * returning the ENR from it.
    */
  def enrRequest: CallOut[A, Proc.ENRRequest]
}

object DiscoveryNetwork {
  type ENRSeq = Long

  /** Pair up requests with responses in the RPC. */
  sealed trait Proc {
    type Req
    type Res
  }
  object Proc {
    trait Ping extends Proc {
      type Req = Option[ENRSeq]
      type Res = Option[ENRSeq]
    }

    trait FindNode extends Proc {
      type Req = PublicKey
      type Res = Seq[Node]
    }

    trait ENRRequest extends Proc {
      type Req = Unit
      type Res = EthereumNodeRecord
    }
  }

  /** Represents a request-response call to a remote address.
    *
    * Raises TimeoutException if the peer doesn't respond.
    */
  type CallOut[A, P <: Proc] = A => P#Req => Task[P#Res]

  /** The identity and address of the remote caller as deducted from the incoming packet. */
  case class Caller[A](publicKey: PublicKey, address: A)

  /** Incoming request with its handler. */
  case class Call[P <: Proc](request: P#Req, respond: P#Res => Task[Unit])

  /** An incoming call the local peer should respond to. */
  sealed trait CallIn[A, P <: Proc] {
    def caller: Caller[A]
    def call: Call[P]
  }
  object CallIn {
    case class Ping[A](caller: Caller[A], call: Call[Proc.Ping]) extends CallIn[A, Proc.Ping]
    case class FindNode[A](caller: Caller[A], call: Call[Proc.FindNode]) extends CallIn[A, Proc.FindNode]
    case class ENRRequest[A](caller: Caller[A], call: Call[Proc.ENRRequest]) extends CallIn[A, Proc.ENRRequest]
  }

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

      private val expirationMillis = messageExpiration.toMillis

      private val currentTimeMillis = clock.monotonic(MILLISECONDS)

      private val maxNeighborsPerPacket = getMaxNeighborsPerPacket

      /** Merge incoming connections and the requests arriving on them into a common
        * stream that can be responded to concurrently.
        */
      override val requests: Observable[CallIn[A, _]] =
        peerGroup
          .nextServerEvent()
          .toObservable
          .collect {
            case ChannelCreated(channel, release) => channel -> release
          }
          .mergeMap {
            case (channel, release) =>
              observeChannel(channel).guarantee(release).onErrorHandleWith {
                case ex: TimeoutException =>
                  Observable.empty
                case NonFatal(ex) =>
                  logger.debug(s"Error on channel from ${channel.to}: $ex")
                  Observable.empty
              }
          }

      /** Collect the incoming requests into a stream of calls. */
      private def observeChannel(channel: Channel[A, Packet]): Observable[CallIn[A, _]] = {
        channel
          .nextMessage()
          .toObservable
          .timeoutOnSlowUpstream(messageExpiration) // Messages older than this would be ignored anyway.
          .collect {
            case MessageReceived(packet) => packet
          }
          .mapEval { packet =>
            currentTimeMillis.map(packet -> _)
          }
          .flatMap {
            case (packet, timestamp) =>
              Packet.unpack(packet) match {
                case Attempt.Successful((payload, remotePublicKey)) =>
                  payload match {
                    case _: Payload.Response =>
                      // Not relevant on the server channel.
                      Observable.empty

                    case p: Payload.HasExpiration[_] if p.expiration < timestamp - expirationMillis =>
                      logger.debug(s"Ignoring expired message from ${channel.to}")
                      Observable.empty

                    case p: Payload.Request =>
                      Observable.pure[CallIn[A, _]] {
                        toCallIn(channel, remotePublicKey, packet.hash, p)
                      }
                  }

                case Attempt.Failure(err) =>
                  Observable.raiseError(
                    new IllegalArgumentException(s"Failed to unpack message: $err")
                  )
              }
          }
      }

      /** Turn a request payload from a given peer into a call, pairing up the
        * request with the appropriate response handling.
        */
      private def toCallIn(
          channel: Channel[A, Packet],
          remotePublicKey: PublicKey,
          hash: Hash,
          payload: Payload.Request
      ): CallIn[A, _] = {
        import Payload._
        val caller = Caller(remotePublicKey, channel.to)

        payload match {
          case Ping(_, _, to, _, maybeRemoteEnrSeq) =>
            val call = Call[Proc.Ping](
              maybeRemoteEnrSeq,
              maybeLocalEnrSeq => send(channel, Pong(to, hash, 0, maybeLocalEnrSeq))
            )
            CallIn.Ping(caller, call)

          case FindNode(target, expiration) =>
            val call = Call[Proc.FindNode](
              target,
              nodes =>
                nodes
                  .grouped(maxNeighborsPerPacket)
                  .toList
                  .traverse { group =>
                    send(channel, Neighbors(group, 0))
                  }
                  .void
            )
            CallIn.FindNode(caller, call)

          case ENRRequest(_) =>
            val call = Call[Proc.ENRRequest](
              (),
              enr => send(channel, ENRResponse(hash, enr))
            )
            CallIn.ENRRequest(caller, call)
        }
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
    def toObservable: Observable[A] =
      Observable.repeatEvalF(next).takeWhile(_.isDefined).map(_.get)
  }

  def getMaxNeighborsPerPacket(implicit codec: Codec[Packet]) =
    // TODO: Iteratively expand the number of neighbors until we hit 1280 bits.
    4
}
