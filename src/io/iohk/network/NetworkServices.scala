package io.iohk.network
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.Clock

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.ActorContext
import io.iohk.codecs.nio._
import io.iohk.codecs.nio.auto._
import io.iohk.network.NodeStatus.NodeState
import io.iohk.network.discovery.DiscoveryListener.DiscoveryListenerRequest
import io.iohk.network.discovery.DiscoveryManager.DiscoveryRequest
import io.iohk.network.discovery.db.DummyKnownNodeStorage
import io.iohk.network.discovery._
import io.iohk.network.telemetry.InMemoryTelemetry

object NetworkServices {

  def networkDiscovery(clock: Clock, peerConfig: PeerConfig, discoveryConfig: DiscoveryConfig): NetworkDiscovery =
    new DiscoveryManagerAdapter(discoveryManagerBehavior(clock, peerConfig, discoveryConfig))

  private def discoveryManagerBehavior(
      clock: Clock,
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

    val codec: NioCodec[DiscoveryWireMessage] = NioCodec[DiscoveryWireMessage]

    val discoveryBehavior = DiscoveryManager.behaviour(
      discoveryConfig,
      new DummyKnownNodeStorage(clock) with InMemoryTelemetry,
      nodeState,
      clock,
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
      new InetSocketAddress("localhost", peerConfig.transportConfig.tcpTransportConfig.get.bindAddress.getPort + 1)

    val serverAddress = peerConfig.transportConfig.tcpTransportConfig.get.natAddress

    NodeInfo(peerConfig.nodeId.id, discoveryAddress, serverAddress, Capabilities(0))
  }
}
