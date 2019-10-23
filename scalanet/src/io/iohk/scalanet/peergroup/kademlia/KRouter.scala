package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.peergroup.kademlia.KRouter.{Config, NodeRecord, NodeRecordIndex}
import monix.eval.Task
import monix.execution.Ack.Continue
import monix.execution.{Ack, Scheduler}
import monix.reactive.subjects.ConcurrentSubject
import org.slf4j.LoggerFactory
import scodec.bits.BitVector

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class KRouter[A](
    val config: Config[A],
    val network: KNetwork[A],
    val clock: Clock = Clock.systemUTC(),
    val uuidSource: () => UUID = () => UUID.randomUUID()
)(
    implicit scheduler: Scheduler
) {

  private val log = LoggerFactory.getLogger(getClass)

  private val nodeRecordIndex = new AtomicReference(
    NodeRecordIndex(new KBuckets(config.nodeRecord.id, clock), Map(config.nodeRecord.id -> config.nodeRecord))
  )

  private val additions = ConcurrentSubject.publish[NodeRecord[A]]

  additions.subscribe(doAdd)

  addAll(config.knownPeers)

  info("Executing initial enrolment process to join the network...")
  Try(Await.result(lookup(config.nodeRecord.id), 2 seconds)).toEither match {
    case Left(t) =>
      info(s"Enrolment lookup failed with exception: $t")
      debug(s"Enrolment failure stacktrace: ${t.getStackTrace.mkString("\n")}")
    case Right(nodes) =>
      debug(s"Enrolment looked completed with network nodes ${nodes.mkString(",")}")
      info(
        s"Initialization complete. ${nodeRecords.size} peers identified " +
          s"(of which 1 is myself and ${config.knownPeers.size} are preconfigured bootstrap peers)."
      )
  }

  network.kRequests.foreach {
    case (FindNodes(uuid, nodeRecord, targetNodeId), responseHandler) =>
      debug(
        s"Received request FindNodes(${nodeRecord.id.toHex}, $nodeRecord, ${targetNodeId.toHex})"
      )
      add(nodeRecord)

      val result = Nodes(uuid, config.nodeRecord, embellish(kBuckets.closestNodes(targetNodeId, config.k)))

      sendResponse(result, responseHandler, nodeRecord.routingAddress)
    case (Ping(uuid, nodeRecord), responseHandler) =>
      debug(
        s"Received request Ping(${nodeRecord.id.toHex}, $nodeRecord)"
      )
      add(nodeRecord)
      sendResponse(Pong(uuid, config.nodeRecord), responseHandler, nodeRecord.routingAddress)
  }

  def get(key: BitVector): Future[NodeRecord[A]] = {
    debug(s"get(${key.toHex})")
    getLocally(key).recoverWith { case _ => getRemotely(key) }.recoverWith { case t => giveUp(key, t) }
  }

  def add(nodeRecord: NodeRecord[A]): Ack = {
    additions.onNext(nodeRecord)
  }

  private def sendResponse(response: KResponse[A], responseHandler: Option[KResponse[A]] => Task[Unit], to: A): Unit = {
    responseHandler(Some(response)).runToFuture
      .onComplete {
        case Failure(t) =>
          log.info(
            s"Pong response ${response.requestId} to $to failed with exception: $t"
          )
        case _ =>
      }
  }
  
  private def addAll(nodeRecords: Set[NodeRecord[A]]): Future[Ack] = {
    def loop(records: List[NodeRecord[A]]): Future[Ack] = records match {
      case Nil =>
        Continue
      case h :: t =>
        add(h).flatMap { _ =>
          loop(t)
        }
    }
    loop(nodeRecords.toList)
  }

  private def doAdd: NodeRecord[A] => Future[Ack] = { nodeRecord =>
    val (iBucket, bucket) = kBuckets.getBucket(nodeRecord.id)
    debug(s"Handling potential addition of candidate (${nodeRecord.id.toHex}, $nodeRecord) to ibucket $iBucket.")
    debug(s"iBucket($iBucket) = $bucket")
    if (bucket.size < config.k) {
      addNodeRecord(nodeRecord)
      Continue
    } else { // the bucket is full
      // ping the bucket's least recently seen node (i.e. the one at the head) to see what to do
      val recordToPing = nodeRecords(bucket.head)
      network
        .ping(recordToPing, Ping(uuidSource(), config.nodeRecord))
        .runToFuture
        .flatMap { _ =>
          // if it does respond, it is moved to the tail and the other node record discarded.
          touchNodeRecord(recordToPing)
          debug(s"Ping to ${recordToPing.id.toHex} in bucket $iBucket successful.")
          info(
            s"Moving ${recordToPing.id} to head of bucket $iBucket. Discarding (${nodeRecord.id.toHex}, $nodeRecord) as routing table candidate."
          )
          debug(s"iBucket($iBucket) = $bucket")
          Continue
        }
        .recover {
          case _ =>
            // if that node fails to respond, it is evicted from the bucket and the other node inserted (at the tail)
            assert(bucket.size < config.k)
            removeNodeRecord(recordToPing)
            addNodeRecord(nodeRecord)
            debug(s"Ping to ${recordToPing.id.toHex} in bucket $iBucket failed.")
            info(s"Replacing ${recordToPing.id.toHex} with new entry (${nodeRecord.id.toHex}, $nodeRecord).")
            Continue
        }
    }
  }

  private def addNodeRecord(nodeRecord: NodeRecord[A]): Unit = {
    nodeRecordIndex.set(NodeRecordIndex(kBuckets.add(nodeRecord.id), nodeRecords + (nodeRecord.id -> nodeRecord)))
  }

  private def removeNodeRecord(nodeRecord: NodeRecord[A]): Unit = {
    nodeRecordIndex.set(NodeRecordIndex(kBuckets.remove(nodeRecord.id), nodeRecords - nodeRecord.id))
  }

  private def touchNodeRecord(nodeRecord: NodeRecord[A]): Unit = {
    nodeRecordIndex.set(NodeRecordIndex(kBuckets.touch(nodeRecord.id), nodeRecords))
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

      val requestId = uuidSource()

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
        .runToFuture
        .recover {
          case t: Throwable =>
            info(s"Query to node $knownNode failed due to exception $t.")
            Seq.empty
        }
    }

    def loop(closestNodes: Seq[BitVector]): Future[Seq[NodeRecord[A]]] = {

      val alphaClosestToQuery: Seq[BitVector] = getAlphaClosestNodes(querySet, closestNodes)

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

    val closestKnownNodes: Seq[BitVector] = kBuckets
      .closestNodes(targetNodeId, config.k + 1)
      .filterNot(myself)
      .take(config.k)

    debug(
      s"Starting lookup for target node ${targetNodeId.toHex} with starting nodes {${closestKnownNodes.map(_.toHex).mkString(", ")}}"
    )

    val nodeResult: Future[Seq[NodeRecord[A]]] = loop(closestKnownNodes)

    nodeResult.foreach(nodeRecords => debug(lookupReport(targetNodeId, nodeRecords)))

    nodeResult.transform(_ => Success(kBucketsLookup(targetNodeId)))
  }

  private def getAlphaClosestNodes(querySet: mutable.Set[BitVector], closestNodes: Seq[BitVector]): Seq[BitVector] = {
    val (firstK, rest) = closestNodes.filterNot(myself).splitAt(config.k)
    val notQueriedYet = firstK.filterNot(querySet.contains)
    if (notQueriedYet.isEmpty)
      notQueriedYet
    else
      (notQueriedYet ++ rest.filterNot(querySet.contains)).take(config.alpha)
  }

  private def myself: BitVector => Boolean = {
    _ == config.nodeRecord.id
  }

  private def lookupReport(targetNodeId: BitVector, nodeRecords: Seq[KRouter.NodeRecord[A]]): String = {

    if (nodeRecords.isEmpty) {
      s"Lookup to ${targetNodeId.toHex} returned no results."
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

  private[kademlia] def kBuckets: KBuckets = {
    nodeRecordIndex.get().kBuckets
  }

  private[kademlia] def nodeRecords: Map[BitVector, NodeRecord[A]] = {
    nodeRecordIndex.get().nodeRecords
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

  case class NodeRecordIndex[A](kBuckets: KBuckets, nodeRecords: Map[BitVector, NodeRecord[A]])
}
