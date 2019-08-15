package io.iohk.scalanet.peergroup.kademlia

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.Nodes
import io.iohk.scalanet.peergroup.kademlia.KRouter.{Config, NodeRecord}
import monix.execution.Scheduler
import org.slf4j.LoggerFactory
import scodec.bits.BitVector

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class KRouter[A](val config: Config[A], val network: KNetwork[A])(
    implicit scheduler: Scheduler
) {

  private val log = LoggerFactory.getLogger(getClass)
  val kBuckets = new KBuckets(config.nodeRecord.id)
  val nodeRecords = new ConcurrentHashMap[BitVector, NodeRecord[A]].asScala

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
  network.findNodes.foreach {
    case (findNodesRequest, responseHandler) =>
      val (uuid, nodeRecord, targetNodeId) =
        (findNodesRequest.requestId, findNodesRequest.nodeRecord, findNodesRequest.targetNodeId)

      debug(
        s"Received request FindNodes(${nodeRecord.id.toHex}, $nodeRecord, ${targetNodeId.toHex})"
      )
      add(nodeRecord)
      val result = embellish(kBuckets.closestNodes(targetNodeId, config.k))
      responseHandler(Nodes(uuid, config.nodeRecord, result))
        .onComplete {
          case Failure(t) =>
            log.info(
              s"Nodes response $uuid to ${nodeRecord.routingAddress} failed with exception: $t"
            )
          case _ =>
        }
  }

  def get(key: BitVector): Future[NodeRecord[A]] = {
    debug(s"get(${key.toHex})")
    getLocally(key).recoverWith { case _ => getRemotely(key) }.recoverWith { case t => giveUp(key, t) }
  }

  def add(nodeRecord: NodeRecord[A]): Unit = {
    kBuckets.add(nodeRecord.id)
    nodeRecords.put(nodeRecord.id, nodeRecord)
    debug(s"Added record (${nodeRecord.id.toHex}, $nodeRecord) to the routing table.")
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

    val querySet = newConcurrentSet[BitVector]

    def query(knownNode: BitVector): Future[Seq[NodeRecord[A]]] = {

      val requestId = UUID.randomUUID()

      val findNodesRequest = FindNodes(
        requestId = requestId,
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

      querySet.add(knownNode)

      network
        .findNodes(knownNodeRecord, findNodesRequest)
        .map { kNodesResponse: Nodes[A] =>
          debug(
            s"Received Nodes response " +
              s"RequestId = ${kNodesResponse.requestId}, " +
              s"From = (${kNodesResponse.nodeRecord.id.toHex}, ${kNodesResponse.nodeRecord})," +
              s"Results = ${kNodesResponse.nodes.map(_.id.toHex).mkString(",")}."
          )

          kNodesResponse.nodes
        }
        .recover {
          case t: Throwable =>
            info(s"Query to node $knownNode failed due to exception $t.")
            Seq.empty
        }
    }

    def loop(closestNodes: Seq[BitVector]): Future[Seq[NodeRecord[A]]] = {

      val alphaClosestToQuery: Seq[BitVector] = closestNodes
        .filterNot(querySet.contains)
        .filterNot(_ == config.nodeRecord.id)
        .take(config.alpha)

      val treeResults: Future[Seq[Seq[NodeRecord[A]]]] = Future.traverse(alphaClosestToQuery) { knownNode: BitVector =>
        val queryResult: Future[Seq[NodeRecord[A]]] = query(knownNode)

        queryResult.flatMap { nodeRecords: Seq[NodeRecord[A]] =>
          nodeRecords.foreach(add)

          val kIds = nodeRecords.map(_.id)
          loop((closestNodes ++ kIds).sorted(xorOrdering)).map(nextRecords => nodeRecords ++ nextRecords)
        }
      }
      treeResults.map(_.flatten)
    }

    val closestKnownNodes: Seq[BitVector] =
      kBuckets.closestNodes(targetNodeId, config.alpha).filterNot(_ == config.nodeRecord.id)

    debug(s"Starting lookup for target node ${targetNodeId} with starting nodes ${closestKnownNodes.mkString(", ")}")

    val nodeResult: Future[Seq[NodeRecord[A]]] = loop(closestKnownNodes)

    nodeResult.foreach(nodeRecords => debug(lookupReport(targetNodeId, nodeRecords)))

    nodeResult.transform(_ => Success(kBucketsLookup(targetNodeId)))
  }

  private def lookupReport(targetNodeId: BitVector, nodeRecords: Seq[KRouter.NodeRecord[A]]): String = {

    if (nodeRecords.isEmpty) {
      s"Lookup to $targetNodeId returned no results."
    } else {
      val ids = nodeRecords.map(_.id).sorted(new XorOrdering(targetNodeId)).reverse

      val ds: Map[BitVector, (NodeRecord[A], BigInt)] =
        nodeRecords.map(nodeRecord => (nodeRecord.id, (nodeRecord, Xor.d(nodeRecord.id, targetNodeId)))).toMap

      val rep = ids.map(nodeId => ds(nodeId)).mkString("\n| ")

      s"""
         | Lookup to target ${targetNodeId.toHex} returned
         | $rep
         |""".stripMargin
    }
  }

  private def kBucketsLookup(targetNodeId: BitVector): Seq[NodeRecord[A]] = {
    embellish(kBuckets.closestNodes(targetNodeId, config.k))
  }

  private def embellish(ids: Seq[BitVector]): Seq[NodeRecord[A]] = {
    ids.map(id => nodeRecords(id))
  }

  private def newConcurrentSet[T]: mutable.Set[T] = {
    java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap[T, java.lang.Boolean]).asScala
  }

  private def debug(msg: => String): Unit = {
    if (log.isDebugEnabled) {
      log.debug(s"${config.nodeRecord.id.toHex} $msg")
    }
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
