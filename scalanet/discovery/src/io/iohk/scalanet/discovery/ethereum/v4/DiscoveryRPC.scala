package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.crypto.{PublicKey}
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import monix.eval.Task

/** The RPC method comprising the Discovery protocol between peers. */
trait DiscoveryRPC[A] {
  import DiscoveryRPC.{Call, Proc}

  /** Sends a Ping request to the node, waits for the correct Pong response,
    * and returns the ENR sequence, if the Pong had one.
    */
  def ping: Call[A, Proc.Ping]

  /** Sends a FindNode request to the node and collects Neighbours responses
    * until a timeout or if the maximum expected number of nodes are returned.
    */
  def findNode: Call[A, Proc.FindNode]

  /** Sends an ENRRequest to the node and waits for the correct ENRResponse,
    * returning the ENR from it.
    */
  def enrRequest: Call[A, Proc.ENRRequest]
}

object DiscoveryRPC {
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

  /** Represents a request-response call to or from a remote peer.
    *
    * When remote, it returns None if the peer doesn't respond.
    * When local, returning None means ignoring the request.
    */
  type Call[A, P <: Proc] = A => P#Req => Task[Option[P#Res]]
}
