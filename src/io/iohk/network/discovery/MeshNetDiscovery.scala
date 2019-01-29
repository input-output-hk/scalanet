package io.iohk.network.discovery
import java.net.InetSocketAddress

import io.iohk.network.transport.Transports
import io.iohk.network.{NodeId, PeerConfig}

/**
  * Implements a mesh overlay that selects peers within a
  * given distance to add to a routing table.
  * This table is then allows an implementation of the peer lookup
  * that will eventually converge to the destination.
  *
  * @param peerConfig This is the nodes own peerConfig
  * @param bootstrapPeerConfig This is the peerConfig of a bootstrap node.
  * @param transports helpers to obtain network transport instances.
  */
class MeshNetDiscovery(peerConfig: PeerConfig, bootstrapPeerConfig: PeerConfig, transports: Transports)
    extends NetworkDiscovery {

  override def nearestPeerTo(nodeId: NodeId): Option[PeerConfig] = ???

  override def nearestNPeersTo(nodeId: NodeId, n: Int): Seq[PeerConfig] = ???

  override def shutdown(): Unit = ???
}

object MeshNetDiscovery {

  def findNodesHandler(address: InetSocketAddress, message: FindNodes): Unit = ???

  def pingHandler(address: InetSocketAddress, message: Ping): Unit = ???

  case class Ping(peerConfig: PeerConfig)

  case class FindNodes(nodeId: NodeId)
}
