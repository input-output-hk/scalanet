package io.iohk.network.discovery

import akka.util.ByteString
import io.iohk.network.{Capabilities, NodeInfo}

sealed trait DiscoveryWireMessage {

  def messageType: Byte
}

case class Ping(protocolVersion: Int, node: NodeInfo, timestamp: Long, nonce: ByteString) extends DiscoveryWireMessage {
  override def messageType: Byte = Ping.messageType
}

object Ping {
  val messageType: Byte = 0x01
}

case class Pong(node: NodeInfo, token: ByteString, timestamp: Long) extends DiscoveryWireMessage {
  override def messageType: Byte = Pong.messageType

}

object Pong {
  val messageType: Byte = 0x02
}

case class Seek(capabilities: Capabilities, maxResults: Int, timestamp: Long, nonce: ByteString)
    extends DiscoveryWireMessage {
  override def messageType: Byte = Seek.messageType
}

object Seek {
  val messageType: Byte = 0x03
}

case class Neighbors(
    capabilities: Capabilities,
    token: ByteString,
    neighborsWithCapabilities: Int,
    neighbors: Seq[NodeInfo],
    timestamp: Long
) extends DiscoveryWireMessage {
  override def messageType: Byte = Neighbors.messageType
}

object Neighbors {
  val messageType: Byte = 0x04
}

object DiscoveryWireMessage {

  val ProtocolVersion = 1

}
