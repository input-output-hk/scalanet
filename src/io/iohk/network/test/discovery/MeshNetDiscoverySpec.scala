package io.iohk.network.discovery

import io.iohk.network.transport.Transports
import io.iohk.network.transport.tcp.NetUtils.{aRandomAddress, aRandomNodeId}
import io.iohk.network.transport.tcp.TcpTransportConfig
import io.iohk.network.{NetworkConfig, PeerConfig}
import org.scalatest.FlatSpec
import org.scalatest.Matchers._

class MeshNetDiscoverySpec extends FlatSpec {

  behavior of "MeshNetworkDiscovery"

  ignore should "have an initial routing table containing itself and a bootstrap node" in {
    val peerConfig =
      PeerConfig(aRandomNodeId(), NetworkConfig(Some(TcpTransportConfig(aRandomAddress()))))

    val transports = new Transports(peerConfig)

    val bootstrapPeerInfo =
      PeerConfig(aRandomNodeId(), NetworkConfig(Some(TcpTransportConfig(aRandomAddress()))))

    val discovery = new MeshNetDiscovery(peerConfig, bootstrapPeerInfo, transports)

    discovery.nearestPeerTo(peerConfig.nodeId) shouldBe Some(peerConfig)
    discovery.nearestPeerTo(bootstrapPeerInfo.nodeId) shouldBe Some(bootstrapPeerInfo)
  }

  it should "bootstrap with a self lookup" in pending

  it should "perform iterative lookups on the bootstrap result until converging" in pending

  it should "respond to a FindNodes request with the closest nodes in its routing table" in pending

  it should "response to a Ping request with a Pong" in pending

  it should "update the routing table if PeerInfo in a Ping contradicts it" in pending

  it should "update the routing table if PeerInfo in a FindNodes contradicts it" in pending

  it should "update the routing table if a peer fails to respond to a Ping" in pending

  it should "update the routing table if a peer fails to respond to a FindNodes" in pending
}
