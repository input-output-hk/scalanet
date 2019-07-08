package io.iohk.scalanet.peergroup.kademlia

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.peergroup.kademlia.KRouter.Config
import io.iohk.scalanet.peergroup.kademlia.KademliaMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KademliaMessage.KResponse.Nodes
import monix.execution.Scheduler
import org.slf4j.LoggerFactory
import scodec.bits.BitVector

import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.util.Success
import scala.concurrent.duration._

class KRouter[V](val config: Config[V], network: KNetwork[V])(
    implicit scheduler: Scheduler
) {

  private val kBuckets = new KBuckets(config.nodeId)
  private val nodeRecords = new ConcurrentHashMap[BitVector, V].asScala
  private val log = LoggerFactory.getLogger(getClass)

  add(config.nodeId, config.nodeRecord)

  config.knownPeers.foreach {
    case (peerId, peerRecord) => add(peerId, peerRecord)
  }

  info("Joining the network...")
  config.knownPeers.foreach {
    case (id, record) =>
      info(s"Connecting to known peer (${id.toHex}, $record)...")
      Await.result(lookup(config.nodeId), 2 seconds)
  }
  info(
    s"Initialization complete. ${nodeRecords.size} peers identified (of which 1 is myself and ${config.knownPeers.size} are preconfigured bootstrap peers)."
  )

  network.server
    .collect {
      case (channel, message) =>
        message match {
          case FindNodes(uuid, nodeId, nodeRecord, targetNodeId) =>
            debug(
              s"Received FindNodes(${nodeId.toHex}, $nodeRecord, ${targetNodeId.toHex})"
            )
            add(nodeId, nodeRecord)
            val result =
              embellish(kBuckets.closestNodes(targetNodeId, config.k))
            channel
              .sendMessage(
                Nodes(uuid, config.nodeId, config.nodeRecord, result)
              )
              .runAsync
        }
    }
    .subscribe()

  private def debug(msg: String): Unit = {
    log.debug(s"${config.nodeId.toHex} $msg")
  }

  private def info(msg: String): Unit = {
    log.debug(s"${config.nodeId.toHex} $msg")
  }

  def get(key: BitVector): Option[V] = {
    for {
      nodeId <- kBuckets.closestNodes(key, 1).find(_ == key)
      record <- nodeRecords.get(nodeId)
    } yield record
  }

  // lookup process, from page 6 of the kademlia paper
  private def lookup(targetNodeId: BitVector): Future[Seq[(BitVector, V)]] = {
    // start by taking the alpha closest nodes from its kbuckets

    val xorOrdering = new XorOrdering(targetNodeId)

    def loop(querySet: Set[BitVector], closestNodes: Seq[BitVector]): Future[Unit] = {

      debug(
        s"Performing lookup for ${targetNodeId.toHex} with ${closestNodes.length} closest nodes: ${closestNodes.map(_.toHex).mkString(",")}"
      )

      // the initiator then sends find node rpcs to these nodes
      val fs = closestNodes
        .filterNot(nodeId => querySet.contains(nodeId))
        .take(config.alpha)
        .map { knownNode =>
          val findNodesRequest = FindNodes[V](
            UUID.randomUUID(),
            config.nodeId,
            config.nodeRecord,
            knownNode
          )

          network.findNodes(nodeRecords(knownNode), findNodesRequest).flatMap { kNodes: Nodes[V] =>
            // verify uuids of request/response match (TODO)

            kNodes.nodes.foreach { case (id, record) => add(id, record) }

            val kIds = kNodes.nodes.map(_._1)

            val closestNext: Seq[BitVector] =
              (closestNodes ++ kIds).sorted(xorOrdering)

            val querySetNext: Set[BitVector] = querySet + knownNode

            loop(querySetNext, closestNext)
          }
        }

      Future
        .sequence(fs)
        .map(_ => ()) // TODO don't want the whole thing to fail cos one future fails
    }

    val closestKnownNodes: Seq[BitVector] =
      kBuckets.closestNodes(targetNodeId, config.alpha)

    loop(Set.empty, closestKnownNodes).transform(
      _ => Success(embellish(kBuckets.closestNodes(targetNodeId, config.k)))
    )
  }

  private def embellish(ids: Seq[BitVector]): Seq[(BitVector, V)] = {
    ids.map(id => (id, nodeRecords(id)))
  }

  private def add(nodeId: BitVector, nodeRecord: V): Unit = {
    kBuckets.add(nodeId)
    nodeRecords.put(nodeId, nodeRecord)
    debug(s"Added record (${nodeId.toHex}, $nodeRecord) to the routing table.")
  }
}

object KRouter {
  case class Config[V](nodeId: BitVector, nodeRecord: V, knownPeers: Map[BitVector, V], alpha: Int = 3, k: Int = 20)
}
