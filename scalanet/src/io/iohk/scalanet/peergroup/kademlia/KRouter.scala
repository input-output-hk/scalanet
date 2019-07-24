package io.iohk.scalanet.peergroup.kademlia

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.peergroup.kademlia.KRouter.Config
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.Nodes
import monix.execution.Scheduler
import org.slf4j.LoggerFactory
import scodec.bits.BitVector

import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
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

  info("Executing initial enrolment process to join the network...")
  Try(Await.result(lookup(config.nodeId), 2 seconds)).toEither match {
    case Left(t) =>
      info(s"Enrolment lookup failed with exception: $t")
    case Right(nodes) =>
      debug(s"Enrolment looked completed with network nodes ${nodes.map(t => (t._1.toHex, t._2)).mkString(",")}")
      info(
        s"Initialization complete. ${nodeRecords.size} peers identified " +
          s"(of which 1 is myself and ${config.knownPeers.size} are preconfigured bootstrap peers)."
      )
  }

  // Handle findNodes requests...
  network.server
    .collect {
      case (channel, message) =>
        message match {
          case FindNodes(uuid, nodeId, nodeRecord, targetNodeId) =>
            debug(
              s"Received request FindNodes(${nodeId.toHex}, $nodeRecord, ${targetNodeId.toHex})"
            )
            add(nodeId, nodeRecord)
            val result =
              embellish(kBuckets.closestNodes(targetNodeId, config.k))
            channel
              .sendMessage(
                Nodes(uuid, config.nodeId, config.nodeRecord, result)
              )
              .runAsync
              .onComplete {
                case Failure(t) =>
                  log.info(
                    s"Nodes response $uuid to ${channel.to} failed with exception: $t"
                  )
                case _ =>
              }
        }
    }
    .subscribe()

  def get(key: BitVector): Future[V] = {
    debug(s"get(${key.toHex})")
    getLocally(key).recoverWith { case _ => getRemotely(key) }.recoverWith { case t => giveUp(key, t) }
  }

  private def getRemotely(key: BitVector): Future[V] = {
    lookup(key).flatMap(_ => getLocally(key))
  }

  private def getLocally(key: BitVector): Future[V] = {
    toFuture(
      for {
        nodeId <- kBuckets.closestNodes(key, 1).find(_ == key)
        record <- nodeRecords.get(nodeId)
      } yield record,
      new Exception(s"Target node id ${key.toHex} not loaded into kBuckets.")
    )
  }

  private def toFuture[T](o: Option[T], failure: => Throwable): Future[T] = {
    o.fold[Future[T]](Future.failed(failure))(t => Future(t))
  }

  private def giveUp(key: BitVector, t: Throwable): Future[V] = {
    val message = s"Lookup failed for get(${key.toHex}). Got an exception: $t."
    info(message)
    Future.failed(new Exception(message, t))
  }

  // lookup process, from page 6 of the kademlia paper
  private def lookup(targetNodeId: BitVector): Future[Seq[(BitVector, V)]] = {
    // start by taking the alpha closest nodes from its kbuckets

    val xorOrdering = new XorOrdering(targetNodeId)

    def loop(querySet: Set[BitVector], closestNodes: Seq[BitVector]): Future[Unit] = {

      debug(
        s"Performing lookup for ${targetNodeId.toHex} with ${closestNodes.length} " +
          s"closest nodes: ${closestNodes.map(_.toHex).mkString(",")}"
      )

      // the initiator then sends find node rpcs to these nodes
      val fs = closestNodes
        .filterNot(querySet.contains)
        .filterNot(_ == config.nodeId)
        .take(config.alpha)
        .map { knownNode =>
          val findNodesRequest = FindNodes[V](
            requestId = UUID.randomUUID(),
            nodeId = config.nodeId,
            nodeRecord = config.nodeRecord,
            targetNodeId = targetNodeId // knownNode
          )

          val knownNodeRecord = nodeRecords(knownNode)
          debug(
            s"Issuing " +
              s"findNodes request to (${knownNode.toHex}, $knownNodeRecord). " +
              s"RequestId = ${findNodesRequest.requestId}, " +
              s"Target = ${targetNodeId.toHex}."
          )

          network.findNodes(knownNodeRecord, findNodesRequest).flatMap { kNodes: Nodes[V] =>
            // verify uuids of request/response match (TODO)

            debug(
              s"Received Nodes response " +
                s"RequestId = ${kNodes.requestId}, " +
                s"From = (${kNodes.nodeId.toHex}, ${kNodes.nodeRecord})," +
                s"Results = ${kNodes.nodes.map(_._1.toHex).mkString(",")}."
            )

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
      kBuckets.closestNodes(targetNodeId, config.alpha).filterNot(_ == config.nodeId)

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

  private def debug(msg: String): Unit = {
    log.debug(s"${config.nodeId.toHex} $msg")
  }

  private def info(msg: String): Unit = {
    log.info(s"${config.nodeId.toHex} $msg")
  }
}

object KRouter {
  case class Config[V](nodeId: BitVector, nodeRecord: V, knownPeers: Map[BitVector, V], alpha: Int = 3, k: Int = 20)
}
