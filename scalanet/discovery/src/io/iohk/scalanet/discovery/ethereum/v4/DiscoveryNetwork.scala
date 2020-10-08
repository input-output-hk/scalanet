package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg}
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import io.iohk.scalanet.peergroup.PeerGroup
import monix.eval.Task
import monix.tail.Iterant
import scala.concurrent.duration._

/** Present a stateless facade implementing the RPC methods
  * that correspond to the discovery protocol messages on top
  * of the peer group representing the other nodes.
  */
trait DiscoveryNetwork[A] {
  import DiscoveryNetwork.{CallIn, CallOut, Proc}

  /** A stream of incoming requests. */
  def requests: Iterant[Task, CallIn[A, _]]

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
  case class Caller[A](publicKey: PublicKey, remote: A)

  /** Inoming request with its handler. */
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
  )(implicit sigalg: SigAlg): Task[DiscoveryNetwork[A]] = Task {
    new DiscoveryNetwork[A] {}
  }
}
