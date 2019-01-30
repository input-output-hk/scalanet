package io.iohk.network
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.Clock

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.ActorContext
import io.iohk.network.NodeStatus.NodeState
import io.iohk.network.discovery.DiscoveryListener.DiscoveryListenerRequest
import io.iohk.network.discovery.DiscoveryManager.DiscoveryRequest
import io.iohk.network.discovery.db.DummyKnownNodeStorage
import io.iohk.network.discovery._
import io.iohk.codecs.nio.NioCodec
import io.iohk.network.telemetry.InMemoryTelemetry
import io.iohk.network.transport.Transports
import io.iohk.network.transport.tcp.NetUtils.aRandomAddress
import io.iohk.network.transport.tcp.{NetUtils, TcpTransportConfig}

trait NetworkFixture {

  // Each network node should have a single instance of the Transports and
  // a single instance of discovery.
  protected class BaseNetwork(val transports: Transports, val networkDiscovery: NetworkDiscovery)

  def randomBaseNetwork(bootstrap: Option[BaseNetwork]): BaseNetwork = {

    val configuration = NetworkConfig(Some(TcpTransportConfig(aRandomAddress())))

    val peerConfig = PeerConfig(NodeId(NetUtils.randomBytes(NodeId.nodeIdBytes)), configuration)

    val transports = new Transports(peerConfig)

    val networkDiscovery: NetworkDiscovery = discovery(peerConfig, bootstrap.map(_.transports.peerConfig))

    new BaseNetwork(transports, networkDiscovery)
  }

  def networks(fixtures: BaseNetwork*)(testCode: Seq[BaseNetwork] => Any): Unit = {
    try {
      testCode(fixtures)
    } finally {
      fixtures.foreach { fixture =>
        fixture.transports.shutdown()
        fixture.networkDiscovery.shutdown()
      }
    }
  }

  private def discovery(peerConfig: PeerConfig, bootstrapNode: Option[PeerConfig]): NetworkDiscovery = {

    import scala.concurrent.duration._

    val bootstrapNodes = bootstrapNode.map(peerConfig2NodeInfoHack).toSet

    val nodeInfo = peerConfig2NodeInfoHack(peerConfig)

    val discoveryConfig = DiscoveryConfig(
      discoveryEnabled = true,
      interface = "localhost",
      port = nodeInfo.discoveryAddress.getPort,
      bootstrapNodes = bootstrapNodes,
      discoveredNodesLimit = 100,
      scanNodesLimit = 100,
      concurrencyDegree = 100,
      scanInitialDelay = 0 millis,
      scanInterval = 1 minute,
      messageExpiration = 1 minute,
      maxSeekResults = 100,
      multipleConnectionsPerAddress = true,
      blacklistDefaultDuration = 1 minute
    )

    val discoveryBehavior: Behavior[DiscoveryRequest] =
      discoveryManagerBehavior(peerConfig, discoveryConfig)

    new DiscoveryManagerAdapter(discoveryBehavior)
  }

  private def discoveryManagerBehavior(
      peerConfig: PeerConfig,
      discoveryConfig: DiscoveryConfig
  ): Behavior[DiscoveryRequest] = {

    val nodeInfo = peerConfig2NodeInfoHack(peerConfig)

    val nodeState = NodeState(
      nodeInfo.id,
      ServerStatus.Listening(nodeInfo.serverAddress),
      ServerStatus.Listening(nodeInfo.discoveryAddress),
      Capabilities(0)
    )

    val codec = {
      import io.iohk.codecs.nio.auto._
      NioCodec[DiscoveryWireMessage]
    }

    val discoveryBehavior = DiscoveryManager.behaviour(
      discoveryConfig,
      new DummyKnownNodeStorage(clock()) with InMemoryTelemetry,
      nodeState,
      clock(),
      codec,
      listenerFactory(discoveryConfig, codec),
      new SecureRandom(),
      InMemoryTelemetry.registry
    )
    discoveryBehavior
  }

  private def listenerFactory(discoveryConfig: DiscoveryConfig, codec: NioCodec[DiscoveryWireMessage])(
      context: ActorContext[DiscoveryRequest]
  ): ActorRef[DiscoveryListenerRequest] = {

    context.spawn(
      DiscoveryListener.behavior(discoveryConfig, UDPBridge.creator(discoveryConfig, codec)),
      "DiscoveryListener"
    )
  }

  // FIXME Get rid of NodeInfo
  private def peerConfig2NodeInfoHack(peerConfig: PeerConfig): NodeInfo = {
    val discoveryAddress =
      new InetSocketAddress("localhost", peerConfig.networkConfig.tcpTransportConfig.get.bindAddress.getPort + 1)

    val serverAddress = peerConfig.networkConfig.tcpTransportConfig.get.natAddress

    NodeInfo(peerConfig.nodeId.id, discoveryAddress, serverAddress, Capabilities(0))
  }

  private def clock(): Clock = Clock.systemUTC()
}
