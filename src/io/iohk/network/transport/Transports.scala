package io.iohk.network.transport

import java.net.InetSocketAddress

import io.iohk.network.PeerConfig
import io.iohk.codecs.nio.NioCodec
import io.iohk.network.transport.tcp.{NettyTransport, TcpNetworkTransport}

object Transports {
  def usesTcp(peerConfig: PeerConfig): Boolean =
    peerConfig.transportConfig.tcpTransportConfig.isDefined
}

/**
  * Encapsulates the networking resources held by the node (tcp ports, etc).
  * You only want one of these objects in most application configurations.
  * @param peerConfig configuration data for the node.
  */
class Transports(val peerConfig: PeerConfig) {

  private var nettyTransportRef: Option[NettyTransport] = None

  def netty(): Option[NettyTransport] = this.synchronized { // AtomicRef does not work for side-effecting fns
    peerConfig.transportConfig.tcpTransportConfig.map(tcpConfiguration => {
      nettyTransportRef match {
        case Some(nettyTransport) =>
          nettyTransport
        case None =>
          val nettyTransport = new NettyTransport(tcpConfiguration.bindAddress)
          nettyTransportRef = Some(nettyTransport)
          nettyTransport
      }
    })
  }

  def tcp[T: NioCodec]: Option[NetworkTransport[InetSocketAddress, T]] =
    netty().map(nettyTransport => new TcpNetworkTransport[T](nettyTransport))

  def shutdown(): Unit = {
    nettyTransportRef.foreach(_.shutdown())
  }
}
