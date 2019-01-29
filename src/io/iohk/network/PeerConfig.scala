package io.iohk.network

case class PeerConfig(nodeId: NodeId, networkConfig: NetworkConfig, capabilities: Capabilities = Capabilities(0))
