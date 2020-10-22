package io.iohk.scalanet.kademlia

import java.security.SecureRandom
import java.time.Clock
import java.util.{Random, UUID}

import cats.syntax.all._
import cats.data.NonEmptySet
import cats.effect.concurrent.Ref
import com.typesafe.scalalogging.{CanLog, Logger}
import io.iohk.scalanet.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.kademlia.KMessage.{KRequest, KResponse}
import io.iohk.scalanet.kademlia.KRouter.KRouterInternals._
import io.iohk.scalanet.kademlia.KRouter.{Config, NodeRecord}
import monix.eval.Task
import monix.reactive.{Consumer, Observable, OverflowStrategy}
import scodec.bits.BitVector
import scala.collection.immutable.SortedSet

// KRouter is the component that implements the protocol, does the recursive lookups, keeps the state.
class KRouter[A](
    val config: Config[A],
    network: KNetwork[A],
    routerState: Ref[Task, NodeRecordIndex[A]],
    clock: Clock = Clock.systemUTC(),
    uuidSource: () => UUID = () => UUID.randomUUID(),
    rnd: Random = new SecureRandom()
) {
  import KRouter.NodeId

  private implicit val nodeId = NodeId(config.nodeRecord.id.toHex)

  private val logger = Logger.takingImplicit[NodeId](getClass)

  /**
    * Start refresh cycle i.e periodically performs lookup for random node id
    *
    * Differs from process described in paper which is described as:
    * Refresh any bucket to which there were no nodes lookup in the past hour. Refreshing means picking
    * a random ID in bucket range and performing a node search for that id
    *
    * Process described in paper would require generating random id which would fit in correct bucket i.e its xor distance from
    * base id needs particular bit length, which is quite troublesome
    *
    * Due to technical complexities of this process most of kademlia implementations like:go-geth, nim-eth, decides to periodically perform lookups for
    * random ids instead
    */
  private def startRefreshCycle(): Task[Unit] = {
    Observable
      .intervalWithFixedDelay(config.refreshRate, config.refreshRate)
      .consumeWith(Consumer.foreachTask { _ =>
        lookup(KBuckets.generateRandomId(config.nodeRecord.id.length, rnd)).void
      })
  }

  // TODO[PM-1035]: parallelism should be configured by library user
  private val responseTaskConsumer =
    Consumer.foreachParallelTask[(KRequest[A], Option[KResponse[A]] => Task[Unit])](parallelism = 4) {
      case (FindNodes(uuid, nodeRecord, targetNodeId), responseHandler) =>
        for {
          _ <- Task(
            logger.debug(
              s"Received request FindNodes(${nodeRecord.id.toHex}, $nodeRecord, ${targetNodeId.toHex})"
            )
          )
          state <- routerState.get
          closestNodes = state.kBuckets.closestNodes(targetNodeId, config.k).map(state.nodeRecords(_))
          response = Nodes(uuid, config.nodeRecord, closestNodes)
          _ <- add(nodeRecord).startAndForget
          responseTask <- responseHandler(Some(response))
        } yield responseTask

      case (Ping(uuid, nodeRecord), responseHandler) =>
        for {
          _ <- Task(
            logger.debug(
              s"Received request Ping(${nodeRecord.id.toHex}, $nodeRecord)"
            )
          )
          _ <- add(nodeRecord).startAndForget
          response = Pong(uuid, config.nodeRecord)
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
    network.kRequests.asyncBoundary(OverflowStrategy.DropNew(config.serverBufferSize)).consumeWith(responseTaskConsumer)
  }

  /**
    * Starts enrollment process by loading all bootstrap nodes from config and then
    * starting a lookup process for self id
    *
    * @return
    */
  private def enroll(): Task[Set[NodeRecord[A]]] = {
    val loadKnownPeers = Task.traverse(config.knownPeers)(add)
    loadKnownPeers >> lookup(config.nodeRecord.id).attempt.flatMap {
      case Left(t) =>
        Task {
          logger.error(s"Enrolment lookup failed with exception: $t")
          logger.debug(s"Enrolment failure stacktrace: ${t.getStackTrace.mkString("\n")}")
          Set.empty
        }

      case Right(nodes) =>
        Task {
          val nodeIds = nodes.map(_.id)
          val bootIds = config.knownPeers.map(_.id)
          val countSelf = nodeIds.count(myself)
          val countBoot = nodeIds.count(bootIds)
          logger.debug(s"Enrolment looked completed with network nodes ${nodes.mkString(",")}")
          logger.info(
            s"Initialization complete. ${nodes.size} peers identified " +
              s"(of which ${countSelf} is myself and ${countBoot} are among the ${bootIds.size} preconfigured bootstrap peers)."
          )
          nodes
        }
    }
  }

  def get(key: BitVector): Task[NodeRecord[A]] = {
    Task(logger.debug(s"get(${key.toHex})")) *>
      getLocally(key) flatMap {
      case Some(value) => Task.now(value)
      case None => getRemotely(key)
    }
  }

  def remove(nodeId: BitVector): Task[Unit] = {
    routerState.update(current => current.removeNodeRecord(current.nodeRecords(nodeId)))
  }

  def kBuckets: Task[KBuckets[BitVector]] = {
    routerState.get.map(_.kBuckets)
  }

  def nodeRecords: Task[Map[BitVector, NodeRecord[A]]] = {
    routerState.get.map(_.nodeRecords)
  }

  def ping(recToPing: NodeRecord[A]): Task[Boolean] = {
    network
      .ping(recToPing, Ping(uuidSource(), config.nodeRecord))
      .as(true)
      .onErrorHandle(_ => false)

  }

  def add(nodeRecord: NodeRecord[A]): Task[Unit] = {
    Task(logger.info(s"Handling potential addition of candidate (${nodeRecord.id.toHex}, $nodeRecord)")) *> {
      if (myself(nodeRecord.id)) {
        Task.unit
      } else {
        for {
          toPing <- routerState.modify { current =>
            val (_, bucket) = current.kBuckets.getBucket(nodeRecord.id)
            if (bucket.contains(nodeRecord.id)) {
              // We're handling a node we already have, perhaps as a side effect of an incoming request.
              // In this case it's enough to update the timestamp.
              (current.touchNodeRecord(nodeRecord), None)
            } else if (bucket.size < config.k) {
              (current.addNodeRecord(nodeRecord), None)
            } else {
              // the bucket is full, not update it but ping least recently seen node (i.e. the one at the head) to see what to do
              val nodeToPing = current.nodeRecords(bucket.head)
              (current, Some(nodeToPing))
            }
          }
          result <- maybePingAndUpdateState(toPing, nodeRecord)
        } yield result
      }
    }
  }

  private def maybePingAndUpdateState(
      maybeRecordToPing: Option[NodeRecord[A]],
      nodeRecord: NodeRecord[A]
  ): Task[Unit] = {
    maybeRecordToPing match {
      case None => Task.unit
      case Some(nodeToPing) =>
        Task(logger.debug(s"Pinging ${nodeToPing.id.toHex} to check if it needs to be replaced.")) *>
          ping(nodeToPing).ifM(
            // if it does respond, it is moved to the tail and the other node record discarded.
            Task(
              logger.info(
                s"Moving ${nodeToPing.id.toHex} to head of bucket. Discarding (${nodeRecord.id.toHex}, $nodeRecord) as routing table candidate."
              )
            ) *>
              routerState.update(_.touchNodeRecord(nodeToPing)),
            // if that node fails to respond, it is evicted from the bucket and the other node inserted (at the tail)
            Task(logger.info(s"Replacing ${nodeToPing.id.toHex} with new entry (${nodeRecord.id.toHex}, $nodeRecord).")) *>
              routerState.update(_.replaceNodeRecord(nodeToPing, nodeRecord))
          )
    }
  }

  private def getRemotely(key: BitVector): Task[NodeRecord[A]] = {
    lookup(key) *> getLocally(key) flatMap {
      case Some(value) => Task.now(value)
      case None => Task.raiseError(new Exception(s"Target node id ${key.toHex} not found"))
    }

  }

  private def getLocally(key: BitVector): Task[Option[NodeRecord[A]]] = {
    for {
      state <- routerState.get
      nodeId = state.kBuckets.closestNodes(key, 1).find(_ == key)
      record = nodeId.flatMap(state.nodeRecords.get)
    } yield record
  }

  // lookup process, from page 6 of the kademlia paper
  // Lookup terminates when initiator queried and got response from the k closest nodes it has seen
  private def lookup(targetNodeId: BitVector): Task[SortedSet[NodeRecord[A]]] = {
    // Starting lookup process with alpha known nodes from our kbuckets
    val closestKnownNodesTask = routerState.get.map { state =>
      state.kBuckets
        .closestNodes(targetNodeId, config.k + 1)
        .filterNot(myself)
        .take(config.alpha)
        .map(id => state.nodeRecords(id))
    }.memoizeOnSuccess

    implicit val xorNodeOrder = new XorNodeOrder[A](targetNodeId)
    implicit val xorNodeOrdering = xorNodeOrder.xorNodeOrdering

    def query(knownNodeRecord: NodeRecord[A]): Task[Seq[NodeRecord[A]]] = {

      val requestId = uuidSource()

      val findNodesRequest = FindNodes(
        requestId = requestId,
        nodeRecord = config.nodeRecord,
        targetNodeId = targetNodeId
      )

      for {
        _ <- Task(
          logger.debug(
            s"Issuing " +
              s"findNodes request to (${knownNodeRecord.id.toHex}, $knownNodeRecord). " +
              s"RequestId = ${findNodesRequest.requestId}, " +
              s"Target = ${targetNodeId.toHex}."
          )
        )
        kNodesResponse <- network.findNodes(knownNodeRecord, findNodesRequest)
        _ <- Task(
          logger.debug(
            s"Received Nodes response " +
              s"RequestId = ${kNodesResponse.requestId}, " +
              s"From = (${kNodesResponse.nodeRecord.id.toHex}, ${kNodesResponse.nodeRecord})," +
              s"Results = ${kNodesResponse.nodes.map(_.id.toHex).mkString(",")}."
          )
        )
      } yield kNodesResponse.nodes
    }

    def handleQuery(to: NodeRecord[A]): Task[QueryResult[A]] = {
      query(to).attempt.flatMap {
        case Left(ex) =>
          Task(logger.debug(s"findNodes request for $to failed: $ex")) >>
            Task.now(QueryResult.failed(to))

        case Right(nodes) =>
          // Adding node to kbuckets in background to not block lookup process
          add(to).startAndForget.as(QueryResult.succeed(to, nodes))
      }
    }

    def shouldFinishLookup(
        nodesLeftToQuery: Seq[NodeRecord[A]],
        nodesFound: NonEmptySet[NodeRecord[A]],
        lookupState: Ref[Task, Map[BitVector, RequestResult]]
    ): Task[Boolean] = {
      for {
        currentState <- lookupState.get
        closestKnodes = nodesFound.toSortedSet
          .take(config.k)
          .filter(node => currentState.get(node.id).contains(RequestSuccess))
        shouldFinish = nodesLeftToQuery.isEmpty || closestKnodes.size == config.k
      } yield shouldFinish
    }

    def getNodesToQuery(
        currentClosestNode: NodeRecord[A],
        receivedNodes: Seq[NodeRecord[A]],
        nodesFound: NonEmptySet[NodeRecord[A]],
        lookupState: Ref[Task, Map[BitVector, RequestResult]]
    ): Task[Seq[NodeRecord[A]]] = {

      // All nodes which are closer to target, than the closest already found node
      val closestNodes = receivedNodes.filter(node => xorNodeOrder.compare(node, currentClosestNode) < 0)

      // we chose either:
      // k nodes from already found or
      // alpha nodes from closest nodes received
      val (nodesToQueryFrom, nodesToTake) =
        if (closestNodes.isEmpty) (nodesFound.toIterable, config.k) else (closestNodes.toIterable, config.alpha)

      for {
        nodesToQuery <- lookupState.modify { currentState =>
          val unqueriedNodes = nodesToQueryFrom
            .collect {
              case node if !currentState.contains(node.id) => node
            }
            .take(nodesToTake)
            .toList

          val updatedMap = unqueriedNodes.foldLeft(currentState)((map, node) => map + (node.id -> RequestScheduled))
          (updatedMap, unqueriedNodes)
        }
      } yield nodesToQuery
    }

    /**
      * 1. Get alpha closest nodes
      * 2. Send parallel async Find_node request to alpha closest nodes
      * 3. Pick alpha closest nodes from received nodes, and send find_node to them
      * 4. If a round of find nodes do not return node which is closer than a node already seen resend
      *    find_node to k closest not queried yet
      * 5. Terminate when:
      *     - queried and received responses from k closest nodes
      *     - no more nodes to query
      *
      */
    // Due to threading recursive function through flatmap and using Task, this is entirely stack safe
    def recLookUp(
        nodesToQuery: Seq[NodeRecord[A]],
        nodesFound: NonEmptySet[NodeRecord[A]],
        lookupState: Ref[Task, Map[BitVector, RequestResult]]
    ): Task[NonEmptySet[NodeRecord[A]]] = {
      shouldFinishLookup(nodesToQuery, nodesFound, lookupState).flatMap {
        case true =>
          Task.now(nodesFound)
        case false =>
          val (toQuery, rest) = nodesToQuery.splitAt(config.alpha)
          for {
            queryResults <- Task.parTraverseUnordered(toQuery) { knownNode =>
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

            updatedFoundNodes = receivedNodes.foldLeft(nodesFound)(_ add _)

            newNodesToQuery <- getNodesToQuery(nodesFound.head, receivedNodes, updatedFoundNodes, lookupState)

            result <- recLookUp(newNodesToQuery ++ rest, updatedFoundNodes, lookupState)
          } yield result
      }
    }

    closestKnownNodesTask
      .map {
        case h :: t => NonEmptySet.of(h, t: _*).some
        case Nil => none
      }
      .flatMap {
        case None =>
          Task(logger.debug("Lookup finished without any nodes, as bootstrap nodes ")).as(SortedSet.empty)

        case Some(closestKnownNodes) =>
          val initalRequestState: Map[BitVector, RequestResult] =
            closestKnownNodes.toList.map(_.id -> RequestScheduled).toMap
          // All initial nodes are scheduled to request
          val lookUpTask: Task[SortedSet[NodeRecord[A]]] = for {
            // All initial nodes are scheduled to request
            state <- Ref.of[Task, Map[BitVector, RequestResult]](initalRequestState)
            // closestKnownNodes are constrained by alpha, it means there will be at most alpha independent recursive tasks
            results <- Task.parTraverseUnordered(closestKnownNodes.toList) { knownNode =>
              recLookUp(List(knownNode), closestKnownNodes, state)
            }
            records = results.reduce(_ ++ _).toSortedSet
          } yield records

          lookUpTask flatTap { records =>
            Task(logger.debug(lookupReport(targetNodeId, records)))
          }
      }
  }

  private def myself: BitVector => Boolean = {
    _ == config.nodeRecord.id
  }

  private def lookupReport(targetNodeId: BitVector, nodeRecords: SortedSet[KRouter.NodeRecord[A]]): String = {

    if (nodeRecords.isEmpty) {
      s"Lookup to ${targetNodeId.toHex} returned no results."
    } else {
      // Print distances in descending order.
      val rep = nodeRecords.toSeq.reverse
        .map { node =>
          node -> Xor.d(node.id, targetNodeId)
        }
        .mkString("\n| ")

      s"""
         | Lookup to target ${targetNodeId.toHex} returned
         | $rep
         |""".stripMargin
    }
  }
}

object KRouter {
  import scala.concurrent.duration._

  /**
    * @param nodeRecord the node own data
    * @param knownPeers node initial known peers i.e bootstrap nodes
    * @param alpha kademlia concurrency parameter, determines how many FindNodes request will be sent concurrently to peers.
    * @param k kademlia neighbours parameter, how many closest neighbours should be returned in response to FindNodes request
    *          also bucket maximum size. In paper mentioned as replication parameter
    * @param serverBufferSize maximum size of server messages buffer
    * @param refreshRate frequency of kademlia refresh procedure
    */
  case class Config[A](
      nodeRecord: NodeRecord[A],
      knownPeers: Set[NodeRecord[A]],
      alpha: Int = 3,
      k: Int = 20,
      serverBufferSize: Int = 2000,
      refreshRate: FiniteDuration = 15.minutes
  )

  private[scalanet] def getIndex[A](config: Config[A], clock: Clock): NodeRecordIndex[A] = {
    NodeRecordIndex(
      new KBuckets[BitVector](config.nodeRecord.id, clock),
      Map(config.nodeRecord.id -> config.nodeRecord)
    )
  }

  /**
    * Enrolls to kademlia network from provided bootstrap nodes and then start handling incoming requests.
    * Use when finishing of enrollment is required before starting handling of incoming requests
    *
    * @param config discovery config
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
      uuidSource: () => UUID = () => UUID.randomUUID()
  ): Task[KRouter[A]] = {
    for {
      state <- Ref.of[Task, NodeRecordIndex[A]](
        getIndex(config, clock)
      )
      router <- Task.now(new KRouter(config, network, state, clock, uuidSource))
      _ <- router.enroll()
      // TODO: These should be fibers that get cleaned up.
      _ <- router.startServerHandling().startAndForget
      _ <- router.startRefreshCycle().startAndForget
    } yield router
  }

  /**
    * Enrolls to kademlia network and start handling incoming requests and refresh buckets cycle in parallel
    *
    *
    * @param config discovery config
    * @param network underlying kademlia network
    * @param clock clock used in kbuckets, default is UTC
    * @param uuidSource source to generate uuids, default is java built in UUID generator
    * @tparam A type of addressing
    * @return initialised kademlia router which handles incoming requests
    */
  //TODO consider adding possibility of having lookup process and server processing on different schedulers
  def startRouterWithServerPar[A](
      config: Config[A],
      network: KNetwork[A],
      clock: Clock = Clock.systemUTC(),
      uuidSource: () => UUID = () => UUID.randomUUID()
  ): Task[KRouter[A]] = {
    Ref
      .of[Task, NodeRecordIndex[A]](
        getIndex(config, clock)
      )
      .flatMap { state =>
        Task.now(new KRouter(config, network, state, clock, uuidSource)).flatMap { router =>
          Task.parMap3(
            router.enroll(), // The results should be ready when we return the router.
            router.startServerHandling().startAndForget,
            router.startRefreshCycle().startAndForget
          )((_, _, _) => router)
        }
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

  private case class NodeId(val value: String) extends AnyVal
  private object NodeId {

    /** Prepend the node ID to each log message. */
    implicit val CanLogNodeId: CanLog[NodeId] = new CanLog[NodeId] {
      override def logMessage(originalMsg: String, a: NodeId): String = s"${a.value} $originalMsg"
    }
  }

  private[scalanet] object KRouterInternals {
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

    case class NodeRecordIndex[A](kBuckets: KBuckets[BitVector], nodeRecords: Map[BitVector, NodeRecord[A]]) {
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
