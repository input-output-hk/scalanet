package io.iohk.network.discovery

import akka.actor.Scheduler
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import akka.{actor => untyped}
import io.iohk.network.discovery.DiscoveryManager.{DiscoveredNodes, DiscoveryRequest, GetDiscoveredNodes}
import io.iohk.network.{NodeId, PeerConfig}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class DiscoveryManagerAdapter(discoveryManagerBehavior: Behavior[DiscoveryRequest]) extends NetworkDiscovery {

  private implicit val futureTimeout: Duration = 1 minute
  private implicit val askTimeout: Timeout = 1 minute

  private implicit val discoveryActorSystem: untyped.ActorSystem = untyped.ActorSystem("discoveryManagerSystem")
  private implicit val scheduler: Scheduler = discoveryActorSystem.scheduler

  private val discoveryManager = discoveryActorSystem.spawn(discoveryManagerBehavior, "discoveryManager")

  override def nearestPeerTo(nodeId: NodeId): Option[PeerConfig] = {
    val futureResult: Future[DiscoveredNodes] = discoveryManager ? GetDiscoveredNodes

    val discoveredNodes: DiscoveredNodes = Await.result(futureResult, futureTimeout)

    discoveredNodes.nodes
      .find(knownNode => NodeId(knownNode.node.id) == nodeId)
      .map(_.node.toPeerInfo)
  }

  override def nearestNPeersTo(nodeId: NodeId, n: Int): Seq[PeerConfig] = {
    val futureResult: Future[DiscoveredNodes] = discoveryManager ? GetDiscoveredNodes

    val discoveredNodes: DiscoveredNodes = Await.result(futureResult, futureTimeout)

    discoveredNodes.nodes.map(_.node.toPeerInfo).take(n).toSeq
  }

  override def shutdown(): Unit =
    discoveryActorSystem.terminate()
}
