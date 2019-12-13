package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import cats.data.NonEmptyList
import cats.effect.concurrent.Ref
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.peergroup.kademlia.KMessage.{KRequest, KResponse}
import io.iohk.scalanet.peergroup.kademlia.KRouter.KRouterInternals._
import io.iohk.scalanet.peergroup.kademlia.KRouter.{Config, NodeRecord}
import monix.eval.Task
import monix.reactive.{Consumer, OverflowStrategy}
import org.slf4j.LoggerFactory
import scodec.bits.BitVector

class KRouter[A](
    val config: Config[A],
    val network: KNetwork[A],
    val clock: Clock = Clock.systemUTC(),
    val uuidSource: () => UUID = () => UUID.randomUUID()
) {

  private val log = LoggerFactory.getLogger(getClass)

  // It needs to be atomic reference as it is modified from multiple threads, server or client ones
  private val nodeRecordIndex = new AtomicReference(
    NodeRecordIndex(new KBuckets(config.nodeRecord.id, clock), Map(config.nodeRecord.id -> config.nodeRecord))
  )

  private def addNodeRecord(nodeRecord: NodeRecord[A]): Unit = {
    nodeRecordIndex.updateAndGet(_.addNodeRecord(nodeRecord))
    ()
  }

  private def removeNodeRecord(nodeRecord: NodeRecord[A]): Unit = {
    nodeRecordIndex.updateAndGet(_.removeNodeRecord(nodeRecord))
    ()
  }

  private def touchNodeRecord(nodeRecord: NodeRecord[A]): Unit = {
    nodeRecordIndex.updateAndGet(_.touchNodeRecord(nodeRecord))
    ()
  }

  private def replaceNodeRecord(oldNode: NodeRecord[A], newNode: NodeRecord[A]): Unit = {
    nodeRecordIndex.updateAndGet(_.replaceNodeRecord(oldNode, newNode))
    ()
  }

  // TODO: parallelism should be configured by library user
  private val responseTaskConsumer =
    Consumer.foreachParallelTask[(KRequest[A], Option[KResponse[A]] => Task[Unit])](parallelism = 4) {
      case (FindNodes(uuid, nodeRecord, targetNodeId), responseHandler) =>
        debug(
          s"Received request FindNodes(${nodeRecord.id.toHex}, $nodeRecord, ${targetNodeId.toHex})"
        )
        val response = Nodes(uuid, config.nodeRecord, embellish(kBuckets.closestNodes(targetNodeId, config.k)))
        for {
          _ <- add(nodeRecord).startAndForget
          responseTask <- responseHandler(Some(response))
        } yield responseTask

      case (Ping(uuid, nodeRecord), responseHandler) =>
        debug(
          s"Received request Ping(${nodeRecord.id.toHex}, $nodeRecord)"
        )
        val response = Pong(uuid, config.nodeRecord)
        for {
          _ <- add(nodeRecord).startAndForget
          responseTask <- responseHandler(Some(response))
        } yield responseTask
    }

  /**
    * Starts handling incoming requests. Infinite task.
    *
    * @return
    */
  private def startServerHandling(): Task[Unit] = {
    // asyncBoundary means that we are giving up on observable back pressure and the consumer will need consume
    // events as soon as available, it is behaviour similar to ConcurrentSubject
    //TODO: it should be configurable by library user what to do when server does not keep up with requests
    network.kRequests.asyncBoundary(OverflowStrategy.Unbounded).consumeWith(responseTaskConsumer)
  }

  /**
    * Starts enrollment process by loading all bootstrap nodes from config and then
    * starting a lookup process for self id
    *
    * @return
    */
  private def enroll(): Task[Seq[NodeRecord[A]]] = {
    val loadKnownPeers = Task.traverse(config.knownPeers)(add)
    loadKnownPeers.flatMap(_ => lookup(config.nodeRecord.id)).attempt.map {
      case Left(t) =>
        info(s"Enrolment lookup failed with exception: $t")
        debug(s"Enrolment failure stacktrace: ${t.getStackTrace.mkString("\n")}")
        Seq()
      case Right(nodes) =>
        debug(s"Enrolment looked completed with network nodes ${nodes.mkString(",")}")
        info(
          s"Initialization complete. ${nodeRecords.size} peers identified " +
            s"(of which 1 is myself and ${config.knownPeers.size} are preconfigured bootstrap peers)."
        )
        nodes
    }
  }

  def get(key: BitVector): Task[NodeRecord[A]] = {
    debug(s"get(${key.toHex})")
    getLocally(key) match {
      case Some(value) => Task.now(value)
      case None => getRemotely(key)
    }
  }

  def remove(nodeId: BitVector): Unit = {
    removeNodeRecord(nodeRecords(nodeId))
  }

  def kBuckets: KBuckets = {
    nodeRecordIndex.get().kBuckets
  }

  def nodeRecords: Map[BitVector, NodeRecord[A]] = {
    nodeRecordIndex.get().nodeRecords
  }

  def add(nodeRecord: NodeRecord[A]): Task[Unit] = {
    val (iBucket, bucket) = kBuckets.getBucket(nodeRecord.id)
    debug(s"Handling potential addition of candidate (${nodeRecord.id.toHex}, $nodeRecord) to ibucket $iBucket.")
    debug(s"iBucket($iBucket) = $bucket")
    if (bucket.size < config.k) {
      addNodeRecord(nodeRecord)
      Task.pure(())
    } else { // the bucket is full
      // ping the bucket's least recently seen node (i.e. the one at the head) to see what to do
      val recordToPing = nodeRecords(bucket.head)
      network
        .ping(recordToPing, Ping(uuidSource(), config.nodeRecord))
        .map { _ =>
          // if it does respond, it is moved to the tail and the other node record discarded.
          touchNodeRecord(recordToPing)
          debug(s"Ping to ${recordToPing.id.toHex} in bucket $iBucket successful.")
          info(
            s"Moving ${recordToPing.id} to head of bucket $iBucket. Discarding (${nodeRecord.id.toHex}, $nodeRecord) as routing table candidate."
          )
          debug(s"iBucket($iBucket) = $bucket")
        }
        .onErrorHandle { _ =>
          // if that node fails to respond, it is evicted from the bucket and the other node inserted (at the tail)
          assert(bucket.size <= config.k)
          replaceNodeRecord(recordToPing, nodeRecord)
          debug(s"Ping to ${recordToPing.id.toHex} in bucket $iBucket failed.")
          info(s"Replacing ${recordToPing.id.toHex} with new entry (${nodeRecord.id.toHex}, $nodeRecord).")
        }
    }
  }

  private def getRemotely(key: BitVector): Task[NodeRecord[A]] = {
    lookup(key).flatMap { _ =>
      getLocally(key) match {
        case Some(value) => Task.now(value)
        case None => Task.raiseError(new Exception(s"Target node id ${key.toHex} not found"))
      }
    }
  }

  private def getLocally(key: BitVector): Option[NodeRecord[A]] = {
    for {
      nodeId <- kBuckets.closestNodes(key, 1).find(_ == key)
      record <- nodeRecords.get(nodeId)
    } yield record
  }

  // lookup process, from page 6 of the kademlia paper
  // Lookup terminates when initiator queried and got response from the k closest nodes it has seen
  private def lookup(targetNodeId: BitVector): Task[Seq[NodeRecord[A]]] = {
    // Starting lookup process with alpha known nodes from our kbuckets
    val closestKnownNodes: Seq[NodeRecord[A]] = kBuckets
      .closestNodes(targetNodeId, config.k + 1)
      .filterNot(myself)
      .take(config.alpha)
      .map(id => nodeRecords(id))

    val xorOrder = new XorOrder[A](targetNodeId)

    def query(knownNodeRecord: NodeRecord[A]): Task[Seq[NodeRecord[A]]] = {

      val requestId = uuidSource()

      val findNodesRequest = FindNodes(
        requestId = requestId,
        nodeRecord = config.nodeRecord,
        targetNodeId = targetNodeId
      )

      debug(
        s"Issuing " +
          s"findNodes request to (${knownNodeRecord.id.toHex}, $knownNodeRecord). " +
          s"RequestId = ${findNodesRequest.requestId}, " +
          s"Target = ${targetNodeId.toHex}."
      )

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
    }

    def handleQuery(to: NodeRecord[A]): Task[QueryResult[A]] = {
      query(to).attempt.flatMap {
        case Left(_) =>
          Task.now(QueryResult.failed(to))
        case Right(nodes) =>
          for {
            // Adding node to kbuckets in background to not block lookup process
            _ <- add(to).startAndForget
            result <- Task.now(QueryResult.succeed(to, nodes))
          } yield result
      }
    }

    def shouldFinishLookup(
        nodesLeftToQuery: Seq[NodeRecord[A]],
        nodesFound: NonEmptyList[NodeRecord[A]],
        lookupState: Ref[Task, Map[BitVector, RequestResult]]
    ): Task[Boolean] = {
      for {
        currentState <- lookupState.get
        closestKnodes = nodesFound.toList
          .take(config.k)
          .filter(node => currentState.get(node.id).contains(RequestSuccess))
        shouldFinish = nodesLeftToQuery.isEmpty || closestKnodes.size == config.k
      } yield shouldFinish
    }

    def getNodesToQuery(
        currentClosestNode: NodeRecord[A],
        receivedNodes: Seq[NodeRecord[A]],
        nodesFounded: NonEmptyList[NodeRecord[A]],
        lookupState: Ref[Task, Map[BitVector, RequestResult]]
    ) = {

      // All nodes which are closer to target, than the closest already found node
      val closestNodes = receivedNodes.filter(node => xorOrder.compare(node, currentClosestNode) < 0)

      // we chose either:
      // k nodes from already found or
      // alpha nodes from closest nodes received
      val (nodesToQueryFrom, nodesToTake) =
        if (closestNodes.isEmpty) (nodesFounded.toList, config.k) else (closestNodes, config.alpha)

      for {
        nodesToQuery <- lookupState.modify { currentState =>
          val unqueriedNodes = nodesToQueryFrom
            .collect {
              case node if !currentState.contains(node.id) => node
            }
            .take(nodesToTake)

          val updatedMap = unqueriedNodes.foldLeft(currentState)((map, node) => map + (node.id -> RequestScheduled))
          (updatedMap, unqueriedNodes)
        }
      } yield nodesToQuery
    }

    /**
      * 1. Get alpha closest nodes
      * 2. Send parralel async Find_node request to alpha closest nodes
      * 3. Pick alpha closest nodes from received nodes, and send find_node to them
      * 4. If a round of find nodes do not return node which is closer than a node already seen resend
      *    find_node to k closest not queried yet
      * 5. Terminate when:
      *     - queried and recieved responses from k closest nodes
      *     - no more nodes to query
      *
      */
    // Due to threading recursive function through flatmap and using Task, this is entirely stack safe
    def recLookUp(
        nodesToQuery: Seq[NodeRecord[A]],
        nodesFounded: NonEmptyList[NodeRecord[A]],
        lookupState: Ref[Task, Map[BitVector, RequestResult]]
    ): Task[NonEmptyList[NodeRecord[A]]] = {
      shouldFinishLookup(nodesToQuery, nodesFounded, lookupState).flatMap {
        case true =>
          Task.now(nodesFounded)
        case false =>
          val (toQuery, rest) = nodesToQuery.splitAt(config.alpha)
          for {
            queryResults <- Task.wander(toQuery) { knownNode =>
              handleQuery(knownNode)
            }

            _ <- lookupState.update { current =>
              queryResults.foldLeft(current)((map, result) => map + (result.info.to.id -> result.info.result))
            }

            receivedNodes = queryResults
              .collect { case QuerySucceed(_, nodes) => nodes }
              .flatten
              .filterNot(node => myself(node.id))
              .toList

            updatedFoundNodes = (nodesFounded ++ receivedNodes).distinct(xorOrder).sorted(xorOrder)

            newNodesToQuery <- getNodesToQuery(nodesFounded.head, receivedNodes, updatedFoundNodes, lookupState)

            result <- recLookUp(newNodesToQuery ++ rest, updatedFoundNodes, lookupState)
          } yield result
      }
    }

    if (closestKnownNodes.isEmpty) {
      debug("Lookup finished without any nodes, as bootstrap nodes ")
      Task.now(Seq.empty)
    } else {
      // All initial nodes are scheduled to request
      val initalRequestState = closestKnownNodes.foldLeft(Map.empty[BitVector, RequestResult]) { (map, node) =>
        map + (node.id -> RequestScheduled)
      }

      val lookUpTask: Task[Seq[NodeRecord[A]]] = for {
        state <- Ref.of[Task, Map[BitVector, RequestResult]](initalRequestState)
        // closestKnownNodes are constrained by alpha, it means there will be at most alpha independent recursive tasks
        results <- Task.wander(closestKnownNodes) { knownNode =>
          recLookUp(List(knownNode), NonEmptyList.fromListUnsafe(closestKnownNodes.toList), state)
        }
      } yield results.flatMap(_.toList)

      lookUpTask.map { records =>
        debug(lookupReport(targetNodeId, records))
        records
      }
    }
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

  private def embellish(ids: Seq[BitVector]): Seq[NodeRecord[A]] = {
    ids.map(id => nodeRecords(id))
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

  /**
    * Enrolls to kademlia network from provided bootstrap nodes and then start handling incoming requests.
    * Use when finishing of enrollment is required before starting handling of incoming requests
    *
    * @param config discovery konfig
    * @param network underlying kademlia network
    * @param clock clock used in kbuckets, default is UTC
    * @param uuidSource source to generate uuids, default is java built in UUID generator
    * @tparam A type of addressing
    * @return initialised kademlia router which handles incoming requests
    */
  def startRouterWithServerSeq[A](
      config: Config[A],
      network: KNetwork[A],
      clock: Clock = Clock.systemUTC(),
      uuidSource: () => UUID
  ): Task[KRouter[A]] = {
    for {
      router <- Task.now(new KRouter(config, network, clock, uuidSource))
      _ <- router.enroll()
      _ <- router.startServerHandling()
    } yield router
  }

  /**
    * Enrolls to kademlia network and start handling incoming requests in parallel
    *
    *
    * @param config discovery konfig
    * @param network underlying kademlia network
    * @param clock clock used in kbuckets, default is UTC
    * @param uuidSource source to generate uuids, default is java built in UUID generator
    * @tparam A type of addressing
    * @return initialised kademlia router which handles incoming requests
    */
  def startRouterWithServerPar[A](
      config: Config[A],
      network: KNetwork[A],
      clock: Clock = Clock.systemUTC(),
      uuidSource: () => UUID
  ): Task[KRouter[A]] = {
    Task.now(new KRouter(config, network, clock, uuidSource)).flatMap { router =>
      Task.parMap2(router.enroll(), router.startServerHandling())((_, _) => router)
    }
  }

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

  private[scalanet] object KRouterInternals {
    //case class LookupState(foundNodes: Seq[NodeRecord[A]], state: Map[BitVector, RequestResult])

    sealed abstract class RequestResult
    case object RequestFailed extends RequestResult
    case object RequestSuccess extends RequestResult
    case object RequestScheduled extends RequestResult

    case class QueryInfo[A](to: NodeRecord[A], result: RequestResult)
    sealed abstract class QueryResult[A] {
      def info: QueryInfo[A]
    }
    case class QueryFailed[A](info: QueryInfo[A]) extends QueryResult[A]
    case class QuerySucceed[A](info: QueryInfo[A], foundNodes: Seq[NodeRecord[A]]) extends QueryResult[A]

    object QueryResult {
      def failed[A](to: NodeRecord[A]): QueryResult[A] =
        QueryFailed(QueryInfo(to, RequestFailed))

      def succeed[A](to: NodeRecord[A], nodes: Seq[NodeRecord[A]]): QuerySucceed[A] =
        QuerySucceed(QueryInfo(to, RequestSuccess), nodes)

    }

    case class NodeRecordIndex[A](kBuckets: KBuckets, nodeRecords: Map[BitVector, NodeRecord[A]]) {
      def addNodeRecord(nodeRecord: NodeRecord[A]): NodeRecordIndex[A] = {
        copy(kBuckets = kBuckets.add(nodeRecord.id), nodeRecords = nodeRecords + (nodeRecord.id -> nodeRecord))
      }

      def removeNodeRecord(nodeRecord: NodeRecord[A]): NodeRecordIndex[A] = {
        copy(kBuckets = kBuckets.remove(nodeRecord.id), nodeRecords = nodeRecords - nodeRecord.id)
      }

      def touchNodeRecord(nodeRecord: NodeRecord[A]): NodeRecordIndex[A] = {
        copy(kBuckets = kBuckets.touch(nodeRecord.id))
      }

      def replaceNodeRecord(oldNode: NodeRecord[A], newNode: NodeRecord[A]): NodeRecordIndex[A] = {
        val withRemoved = removeNodeRecord(oldNode)
        withRemoved.addNodeRecord(newNode)
      }

    }
  }
}
