package io.iohk.network

case class PeerConfig(nodeId: NodeId, transportConfig: TransportConfig, capabilities: Capabilities = Capabilities(0))
