package io.iohk.network.transport.tcp
import java.net.InetSocketAddress

case class TcpTransportConfig(bindAddress: InetSocketAddress, natAddress: InetSocketAddress)

object TcpTransportConfig {
  def apply(bindAddress: InetSocketAddress): TcpTransportConfig =
    TcpTransportConfig(bindAddress, bindAddress)
}
