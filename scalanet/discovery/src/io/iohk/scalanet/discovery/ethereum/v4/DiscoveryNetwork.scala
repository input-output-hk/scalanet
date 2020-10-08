package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.crypto.PublicKey
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import monix.eval.Task
import io.iohk.scalanet.peergroup.PeerGroup

/** Present a stateless facade implementing the RPC methods
  * that correspond to the discovery protocol messages on top
  * of the peer group representing the other nodes.
  */
trait DiscoveryNetwork {

  /** Sends a Ping request to the node, waits for the correct Pong response,
    * and returns the ENR sequence, if the Pong had one.
    *
    * Raises TimeoutException if the node doesn't respond. */
  def ping(to: Node, localEnrSeq: Option[Long]): Task[Option[Long]]

  /** Sends a FindNode request to the node and collects Neighbours responses
    * until a timeout or if the maximum expected number of nodes are returned.
    *
    * Returns empty list if the node doesn't respond.
    */
  def findNode(to: Node, target: PublicKey, bucketSize: Int): Task[Seq[Node]]

  /** Sends an ENRRequest to the node and waits for the correct ENRResponse,
    * returning the ENR from it.
    *
    * Raises TimeoutException if the node doesn't respond.
    */
  def enrRequest(to: Node): Task[EthereumNodeRecord]
}

class DiscoveryNetwork {
  def apply[A](
      peerGroup: PeerGroup[A, Packet],
      toAddress: Node.Address => A
  ): Resource[Task, DiscoveryNetwork]
}
