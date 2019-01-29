package io.iohk.network.discovery

import io.iohk.network.NodeInfo

sealed abstract class DiscoveryClassifier

case class CompatibleNodeFound(nodeInfo: NodeInfo) extends DiscoveryClassifier

case class NodeRemoved(nodeInfo: NodeInfo) extends DiscoveryClassifier
