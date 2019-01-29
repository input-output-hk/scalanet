package io.iohk.network
import io.iohk.network.transport.tcp.TcpTransportConfig

/**
  * @param tcpTransportConfig The configuration of the TcpTransport, if one should be used.
  * @param messageTtl The number of network hops before discarding a message.
  */
case class NetworkConfig(tcpTransportConfig: Option[TcpTransportConfig], messageTtl: Int = 5)
