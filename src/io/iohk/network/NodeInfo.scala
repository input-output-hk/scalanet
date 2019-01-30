package io.iohk.network

import java.net.{Inet6Address, InetAddress, InetSocketAddress, URI}

import akka.util.ByteString
import io.iohk.network.transport.FrameHeader
import io.iohk.network.transport.tcp.TcpTransportConfig
import javax.xml.bind.DatatypeConverter
import io.iohk.network.utils.HexStringCodec._

import scala.util.Try

/**
  * TODO this is currently hardcoded only to work with rlpx on a tcp/ip network.
  * FIXME replace NodeInfo with PeerInfo and NodeId
  */
case class NodeInfo(
                     id: ByteString,
                     discoveryAddress: InetSocketAddress,
                     serverAddress: InetSocketAddress,
                     capabilities: Capabilities
                   ) {

  def getServerUri: URI = {
    val host = getHostName(serverAddress.getAddress)
    new URI(s"enode://${toHexString(id)}@$host:${serverAddress.getPort}?capabilities=${capabilities.byte.toHexString}")
  }

  /**
    * Given an address, returns the corresponding host name for the URI.
    * All IPv6 addresses are enclosed in square brackets.
    *
    * @param address, whose host name will be obtained
    * @return host name associated with the address
    */
  private def getHostName(address: InetAddress): String = {
    val hostName = address.getHostAddress
    address match {
      case _: Inet6Address => s"[$hostName]"
      case _ => hostName
    }
  }

  def toPeerInfo: PeerConfig = {
    val itsNodeId = NodeId(id)
    val itsConfiguration =
      TransportConfig(Some(TcpTransportConfig(serverAddress)), FrameHeader.defaultTtl)
    PeerConfig(itsNodeId, itsConfiguration)
  }
}

object NodeInfo {

  import io.iohk.codecs.nio._
  implicit val NodeInfoEncDec: NioCodec[NodeInfo] = NioCodec[NodeInfo]

  def fromUri(p2pUri: URI, discoveryUri: URI, capabilitiesHex: String): Try[NodeInfo] = Try {
    val nodeId = fromHexString(p2pUri.getUserInfo)
    val p2pAddress = InetAddress.getByName(p2pUri.getHost)
    val udpAddress = InetAddress.getByName(discoveryUri.getHost)
    val p2pTcpPort = p2pUri.getPort
    val udpPort = discoveryUri.getPort
    val capabilities = DatatypeConverter.parseHexBinary(capabilitiesHex)(0)

    NodeInfo(
      nodeId,
      new InetSocketAddress(udpAddress, udpPort),
      new InetSocketAddress(p2pAddress, p2pTcpPort),
      Capabilities(capabilities)
    )
  }
}
