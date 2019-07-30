package io.iohk.scalanet.peergroup.kademlia

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.peergroup.kademlia.KRouter.{Config, NodeRecord}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.Nodes
import monix.execution.Scheduler
import org.slf4j.LoggerFactory
import scodec.bits.BitVector

import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

class KRouter[A](val config: Config[A], network: KNetwork[A])(
    implicit scheduler: Scheduler
) {

  private val kBuckets = new KBuckets(config.nodeRecord.id)
  private val nodeRecords = new ConcurrentHashMap[BitVector, NodeRecord[A]].asScala
  private val log = LoggerFactory.getLogger(getClass)

  add(config.nodeRecord)

  config.knownPeers.foreach(add)

  info("Executing initial enrolment process to join the network...")
  Try(Await.result(lookup(config.nodeRecord.id), 2 seconds)).toEither match {
    case Left(t) =>
      info(s"Enrolment lookup failed with exception: $t")
    case Right(nodes) =>
      debug(s"Enrolment looked completed with network nodes ${nodes.mkString(",")}")
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
          case FindNodes(uuid, nodeRecord, targetNodeId) =>
            debug(
              s"Received request FindNodes(${nodeRecord.id.toHex}, $nodeRecord, ${targetNodeId.toHex})"
            )
            add(nodeRecord)
            val result =
              embellish(kBuckets.closestNodes(targetNodeId, config.k))
            channel
              .sendMessage(Nodes(uuid, config.nodeRecord, result))
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

  def get(key: BitVector): Future[NodeRecord[A]] = {
    debug(s"get(${key.toHex})")
    getLocally(key).recoverWith { case _ => getRemotely(key) }.recoverWith { case t => giveUp(key, t) }
  }

  private def getRemotely(key: BitVector): Future[NodeRecord[A]] = {
    lookup(key).flatMap(_ => getLocally(key))
  }

  private def getLocally(key: BitVector): Future[NodeRecord[A]] = {
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

  private def giveUp(key: BitVector, t: Throwable): Future[NodeRecord[A]] = {
    val message = s"Lookup failed for get(${key.toHex}). Got an exception: $t."
    info(message)
    Future.failed(new Exception(message, t))
  }

  // lookup process, from page 6 of the kademlia paper
  private def lookup(targetNodeId: BitVector): Future[Seq[NodeRecord[A]]] = {
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
        .filterNot(_ == config.nodeRecord.id)
        .take(config.alpha)
        .map { knownNode =>
          val findNodesRequest = FindNodes(
            requestId = UUID.randomUUID(),
            nodeRecord = config.nodeRecord,
            targetNodeId = targetNodeId
          )

          val knownNodeRecord = nodeRecords(knownNode)
          debug(
            s"Issuing " +
              s"findNodes request to (${knownNode.toHex}, $knownNodeRecord). " +
              s"RequestId = ${findNodesRequest.requestId}, " +
              s"Target = ${targetNodeId.toHex}."
          )

          network.findNodes(knownNodeRecord, findNodesRequest).flatMap { kNodes: Nodes[A] =>
            // verify uuids of request/response match (TODO)

            debug(
              s"Received Nodes response " +
                s"RequestId = ${kNodes.requestId}, " +
                s"From = (${kNodes.nodeRecord.id.toHex}, ${kNodes.nodeRecord})," +
                s"Results = ${kNodes.nodes.map(_.id.toHex).mkString(",")}."
            )

            kNodes.nodes.foreach(add)

            val kIds = kNodes.nodes.map(_.id)

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
      kBuckets.closestNodes(targetNodeId, config.alpha).filterNot(_ == config.nodeRecord.id)

    loop(Set.empty, closestKnownNodes).transform(
      _ => Success(embellish(kBuckets.closestNodes(targetNodeId, config.k)))
    )
  }

  private def embellish(ids: Seq[BitVector]): Seq[NodeRecord[A]] = {
    ids.map(id => nodeRecords(id))
  }

  private def add(nodeRecord: NodeRecord[A]): Unit = {
    kBuckets.add(nodeRecord.id)
    nodeRecords.put(nodeRecord.id, nodeRecord)
    debug(s"Added record (${nodeRecord.id.toHex}, $nodeRecord) to the routing table.")
  }

  private def debug(msg: String): Unit = {
    log.debug(s"${config.nodeRecord.id.toHex} $msg")
  }

  private def info(msg: String): Unit = {
    log.info(s"${config.nodeRecord.id.toHex} $msg")
  }
}

object KRouter {
  case class Config[A](nodeRecord: NodeRecord[A], knownPeers: Set[NodeRecord[A]], alpha: Int = 3, k: Int = 20)

  // These node records are derived from Ethereum node records (https://eips.ethereum.org/EIPS/eip-778)
  // TODO node records require an additional
  // signature (why)
  // sequence number (why)
  // compressed public key (why)
  // TODO understand what these things do, which we need an implement.
  case class NodeRecord[A](id: BitVector, routingAddress: A, messagingAddress: A) {
    override def toString: String =
      s"NodeRecord(id = ${id.toHex}, routingAddress = $routingAddress, messagingAddress = $messagingAddress)"
  }
}
