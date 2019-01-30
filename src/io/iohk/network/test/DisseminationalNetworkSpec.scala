package io.iohk.network

import java.net.InetSocketAddress

import io.iohk.network.discovery.NetworkDiscovery
import io.iohk.codecs.nio.auto._
import io.iohk.network.transport.tcp.NetUtils.{aRandomAddress, aRandomNodeId}
import io.iohk.network.transport.tcp.TcpTransportConfig
import io.iohk.network.transport.{Frame, FrameHeader, NetworkTransport, Transports}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, when}
import org.scalatest.FlatSpec
import org.scalatest.mockito.MockitoSugar._

class DisseminationalNetworkSpec extends FlatSpec {

  behavior of "Disseminational Network"

  val nodeId = aRandomNodeId()
  val address = aRandomAddress()
  val transports = mock[Transports]
  val message = "Hello, world!"

  val peer1 =
    PeerConfig(aRandomNodeId(), TransportConfig(Some(TcpTransportConfig(aRandomAddress()))))
  val peer2 =
    PeerConfig(aRandomNodeId(), TransportConfig(Some(TcpTransportConfig(aRandomAddress()))))

  it should "disseminate a message to its peers" in {
    val peers = List(peer1, peer2)
    val discovery = mock[NetworkDiscovery]
    val tcpTransport = mock[NetworkTransport[InetSocketAddress, Frame[String]]]
    //    println(peers)
    peers.foreach(peer => when(discovery.nearestPeerTo(peer.nodeId)).thenReturn(Some(peer)))
    when(discovery.nearestNPeersTo(nodeId, Int.MaxValue)).thenReturn(peers)
    when(transports.peerConfig)
      .thenReturn(PeerConfig(nodeId, TransportConfig(Some(TcpTransportConfig(address)))))
    when(transports.tcp[Frame[String]](any())).thenReturn(Some(tcpTransport))

    val network = new DisseminationalNetwork[String](discovery, transports)

    network.disseminateMessage(message)

    peers.foreach(peer => {
      val expectedMessageFrame = Frame(FrameHeader(nodeId, peer.nodeId), message)
      verify(tcpTransport)
        .sendMessage(peer.transportConfig.tcpTransportConfig.get.bindAddress, expectedMessageFrame)
    })
  }

}
