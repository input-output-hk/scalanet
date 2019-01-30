package io.iohk.network.discovery.db

import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

import akka.util.ByteString
import io.iohk.network.NodeInfo
import io.iohk.network.telemetry.Telemetery

import scala.concurrent.duration.FiniteDuration

// Implementation only for demo-ing purposes
class DummyKnownNodeStorage(clock: Clock) extends KnownNodeStorage {
  self: Telemetery =>

  var nodeMap: Map[ByteString, KnownNode] = Map.empty

  val trackingKnownNodes =
    registry.gauge("known_nodes", new AtomicInteger(getAll().size))

  override def getAll(): Set[KnownNode] = nodeMap.values.toSet

  override def insert(nodeInfo: NodeInfo): Unit = {
    val updatedNode = nodeMap.get(nodeInfo.id).map(_.copy(node = nodeInfo, lastSeen = clock.instant()))
    nodeMap = nodeMap + ((nodeInfo.id, updatedNode.getOrElse(KnownNode(nodeInfo, clock.instant(), clock.instant()))))
    if (updatedNode.isEmpty)
      trackingKnownNodes.incrementAndGet()
  }

  override def remove(nodeInfo: NodeInfo): Unit = {
    if (nodeMap.contains(nodeInfo.id)) trackingKnownNodes.decrementAndGet()
    nodeMap = nodeMap - nodeInfo.id
  }

  //Implementation only for demo-ing purposes
  override def blacklist(nodeInfo: NodeInfo, duration: FiniteDuration): Unit = remove(nodeInfo)

}
