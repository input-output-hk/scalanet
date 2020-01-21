package io.iohk.scalanet.peergroup.kademlia

import java.security.SecureRandom
import java.time.Clock
import java.util
import java.util.{Random, UUID}

import cats.data.NonEmptyList
import cats.effect.concurrent.Ref
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.PeerGroup
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.peergroup.kademlia.KMessage.{KRequest, KResponse}
import io.iohk.scalanet.peergroup.kademlia.KRouter.KRouterInternals._
import io.iohk.scalanet.peergroup.kademlia.KRouter.{Config, NodeRecord}
import io.iohk.scalanet.peergroup.kademlia.KRouterBeta.BroadCastMessage
import monix.eval.Task
import monix.execution.atomic.AtomicLong
import monix.reactive.{Consumer, Observable, OverflowStrategy}
import org.slf4j.LoggerFactory
import scodec.bits.BitVector

import monix.execution.Scheduler.Implicits.global

import scala.collection.mutable

class KRouterBeta[A,M](
                  val config: Config[A],
                  val network: KNetworkBeta[A,KRouterBeta.BroadCastMessage[M]],
                  private val routerState: Ref[Task, NodeRecordIndex[A]],
                  val function: M => Unit,
                  val sizeCache:Int = 5,
                  val clock: Clock = Clock.systemUTC(),
                  val uuidSource: () => UUID = () => UUID.randomUUID(),
                  val rnd: Random = new SecureRandom()
                ){

  private val nextUUID = new mutable.HashMap[BitVector,Long]()
  private val cache = new mutable.HashMap[BitVector,Map[Long,BroadCastMessage[M]]]()
  //_------------------------------------TMP--------------------------------------------------------------------
  import scala.concurrent.duration.Duration
  import monix.execution.Scheduler.Implicits.global
  val castID = config.nodeRecord.id
  def printState():Unit = {
    routerState.get.delayExecution(Duration(3000,"millis")).runAsync(rS => rS match{
      case Left(_) => printState()
      case Right(routState) => {
        //val tmp =  routState.kBuckets.buckets.filter(p => !p.isEmpty).foldLeft(Set():Set[BitVector])((rec,x) => rec ++ x)
        val tmp = routState.nodeRecords.values
        System.out.println("ID: " + castID + ": SIZE: " + tmp.size + " RECORD NODES: " + tmp.foldRight("")((x,rec) => " Elem: " + x.toString() + rec) )
        printState()
      }
    })
  }
  //printState()

  //-----------------------------------------------------------------------------------------------------------


  private val log = LoggerFactory.getLogger(getClass)

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
        lookup(KBuckets.generateRandomId(config.nodeRecord.id.length, rnd)).map(_ => ())
      })
  }

  // TODO[PM-1035]: parallelism should be configured by library user
  private val responseTaskConsumer =
    Consumer.foreachParallelTask[Either[(KRequest[A], Option[KResponse[A]] => Task[Unit]), KRouterBeta.BroadCastMessage[M]]](parallelism = 4) {
      case Left((FindNodes(uuid, nodeRecord, targetNodeId), responseHandler)) =>
        debug(
          s"Received request FindNodes(${nodeRecord.id.toHex}, $nodeRecord, ${targetNodeId.toHex})"
        )
        for {
          state <- routerState.get
          closestNodes = state.kBuckets.closestNodes(targetNodeId, config.k).map(state.nodeRecords(_))
          response = Nodes(uuid, config.nodeRecord, closestNodes)
          _ <- add(nodeRecord).startAndForget
          responseTask <- responseHandler(Some(response))
        } yield responseTask

      case Left((Ping(uuid, nodeRecord), responseHandler)) =>
        debug(
          s"Received request Ping(${nodeRecord.id.toHex}, $nodeRecord)"
        )
        val response = Pong(uuid, config.nodeRecord)
        for {
          _ <- add(nodeRecord).startAndForget
          responseTask <- responseHandler(Some(response))
        } yield responseTask
      case Right(m) => {
        val n:Long = synchronized({
          val res:Long = nextUUID.getOrElse(m.sender,0)
          if(res.equals(m.uuid)) nextUUID.put(m.sender,m.uuid+1)
          res
        })
        if(n.equals(m.uuid)) {
          nodeRecords.map(_.values.toSeq).map(nodes => {
            function(m.message)
            val sMenssages = nodes.map(nod => network.sendMessage(nod, m))
            for(mSend <- sMenssages){
              mSend.runAsyncAndForget
            }

            {
              val senderCache = cache.getOrElse(m.sender, Map())

              def aux(i: Long): Unit = {
                val current = senderCache.get(i)
                if (current.isDefined) {
                  function(current.get.message)
                  nodes.map(nod => network.sendMessage(nod, current.get)).foldRight(())((x, rec) => {
                    x.runAsyncAndForget;
                    rec
                  })
                  aux(i + 1)
                }
              }

              aux(m.uuid + 1)
            }
          })
        }else if(m.uuid < n && m.uuid >= n+sizeCache) {
          cache.put(m.sender,cache.getOrElse(m.sender,Map()) + (m.uuid -> m) )
          Task.eval()
        }else {
          //System.out.println("DELETE MESSAGE")
          Task.eval()
        }
      }
    }

  def broadCastMessage(mess:M):Task[Unit] = {
    val uuid:Long = synchronized({val res:Long = nextUUID.getOrElse(config.nodeRecord.id,0);nextUUID.put(config.nodeRecord.id,res + 1);res})
    nodeRecords.map(_.values.toSeq).map( nodes => {
        nodes.map(nod =>
          network.sendMessage(nod,BroadCastMessage(uuid,config.nodeRecord.id,mess))).foldRight(())((x, rec) => {x.runAsyncAndForget;rec})
    })
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
          s"Initialization complete. ${nodes.size} peers identified " +
            s"(of which 1 is myself and ${config.knownPeers.size} are preconfigured bootstrap peers)."
        )
        nodes
    }
  }

  def get(key: BitVector): Task[NodeRecord[A]] = {
    debug(s"get(${key.toHex})")
    getLocally(key) flatMap {
      case Some(value) => Task.now(value)
      case None => getRemotely(key)
    }
  }

  def remove(nodeId: BitVector): Task[Unit] = {
    routerState.update(current => current.removeNodeRecord(current.nodeRecords(nodeId)))
  }

  def kBuckets: Task[KBuckets] = {
    routerState.get.map(_.kBuckets)
  }

  def nodeRecords: Task[Map[BitVector, NodeRecord[A]]] = {
    routerState.get.map(_.nodeRecords)
  }

  def ping(recToPing: NodeRecord[A]): Task[Boolean] = {
    network
      .ping(recToPing, Ping(uuidSource(), config.nodeRecord))
      .map(_ => true)
      .onErrorHandle(_ => false)

  }

  def add(nodeRecord: NodeRecord[A]): Task[Unit] = {
    info(s"Handling potential addition of candidate (${nodeRecord.id.toHex}, $nodeRecord)")
    for {
      toPing <- routerState.modify { current =>
        val (_, bucket) = current.kBuckets.getBucket(nodeRecord.id)
        if (bucket.size < config.k) {
          (current.addNodeRecord(nodeRecord), None)
        } else {
          // the bucket is full, not update it but ping least recently seen node (i.e. the one at the head) to see what to do
          val nodeToPing = current.nodeRecords(bucket.head)
          (current, Some(nodeToPing))
        }
      }
      result <- pingAndUpdateState(toPing, nodeRecord)
    } yield result
  }

  private def pingAndUpdateState(recordToPing: Option[NodeRecord[A]], nodeRecord: NodeRecord[A]): Task[Unit] = {
    recordToPing match {
      case None => Task.now(())
      case Some(nodeToPing) =>
        for {
          pingResult <- ping(nodeToPing)
          _ <- routerState.update { current =>
            if (pingResult) {
              // if it does respond, it is moved to the tail and the other node record discarded.
              current.touchNodeRecord(nodeToPing)
            } else {
              // if that node fails to respond, it is evicted from the bucket and the other node inserted (at the tail)
              current.replaceNodeRecord(nodeToPing, nodeRecord)
            }
          }

          _ <- Task.eval {
            if (pingResult) {
              info(
                s"Moving ${nodeToPing.id} to head of bucket. Discarding (${nodeRecord.id.toHex}, $nodeRecord) as routing table candidate."
              )
            } else {
              info(s"Replacing ${nodeToPing.id.toHex} with new entry (${nodeRecord.id.toHex}, $nodeRecord).")
            }
          }
        } yield ()
    }
  }

  private def getRemotely(key: BitVector): Task[NodeRecord[A]] = {
    lookup(key).flatMap { _ =>
      getLocally(key) flatMap {
        case Some(value) => Task.now(value)
        case None => Task.raiseError(new Exception(s"Target node id ${key.toHex} not found"))
      }
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
  private def lookup(targetNodeId: BitVector): Task[Seq[NodeRecord[A]]] = {
    // Starting lookup process with alpha known nodes from our kbuckets
    val closestKnownNodesTask = routerState.get.map { state =>
      state.kBuckets
        .closestNodes(targetNodeId, config.k + 1)
        .filterNot(myself)
        .take(config.alpha)
        .map(id => state.nodeRecords(id))
    }.memoizeOnSuccess

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
                         nodesFound: NonEmptyList[NodeRecord[A]],
                         lookupState: Ref[Task, Map[BitVector, RequestResult]]
                       ): Task[Seq[NodeRecord[A]]] = {

      // All nodes which are closer to target, than the closest already found node
      val closestNodes = receivedNodes.filter(node => xorOrder.compare(node, currentClosestNode) < 0)

      // we chose either:
      // k nodes from already found or
      // alpha nodes from closest nodes received
      val (nodesToQueryFrom, nodesToTake) =
      if (closestNodes.isEmpty) (nodesFound.toList, config.k) else (closestNodes, config.alpha)

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
                   nodesFound: NonEmptyList[NodeRecord[A]],
                   lookupState: Ref[Task, Map[BitVector, RequestResult]]
                 ): Task[NonEmptyList[NodeRecord[A]]] = {
      shouldFinishLookup(nodesToQuery, nodesFound, lookupState).flatMap {
        case true =>
          Task.now(nodesFound)
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

            updatedFoundNodes = (nodesFound ++ receivedNodes).distinct(xorOrder).sorted(xorOrder)

            newNodesToQuery <- getNodesToQuery(nodesFound.head, receivedNodes, updatedFoundNodes, lookupState)

            result <- recLookUp(newNodesToQuery ++ rest, updatedFoundNodes, lookupState)
          } yield result
      }
    }
    closestKnownNodesTask.flatMap { closestKnownNodes =>
      if (closestKnownNodes.isEmpty) {
        debug("Lookup finished without any nodes, as bootstrap nodes ")
        Task.now(Seq.empty)
      } else {
        val initalRequestState = closestKnownNodes.foldLeft(Map.empty[BitVector, RequestResult]) { (map, node) =>
          map + (node.id -> RequestScheduled)
        }
        // All initial nodes are scheduled to request
        val lookUpTask: Task[Seq[NodeRecord[A]]] = for {
          // All initial nodes are scheduled to request
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

  private def debug(msg: => String): Unit = {
    if (log.isDebugEnabled) {
      log.debug(s"${config.nodeRecord.id.toHex} $msg")
    }
  }

  private def info(msg: String): Unit = {
    log.info(s"${config.nodeRecord.id.toHex} $msg")
  }
}

object KRouterBeta {
  import scala.concurrent.duration._

  case class BroadCastMessage[M](uuid:Long,sender:BitVector,message: M)

  private[scalanet] def getIndex[A](config: Config[A], clock: Clock): NodeRecordIndex[A] = {
    NodeRecordIndex(new KBuckets(config.nodeRecord.id, clock), Map(config.nodeRecord.id -> config.nodeRecord))
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
  def startRouterWithServerSeq[A,M](
                                   config: Config[A],
                                   network: KNetworkBeta[A,KRouterBeta.BroadCastMessage[M]],
                                   function: M => Unit,
                                   cacheSize:Int = 5,
                                   clock: Clock = Clock.systemUTC(),
                                   uuidSource: () => UUID = () => UUID.randomUUID()
                                 ): Task[KRouterBeta[A,M]] = {
    for {
      state <- Ref.of[Task, NodeRecordIndex[A]](
        getIndex(config, clock)
      )
      router <- Task.eval(new KRouterBeta[A,M](config, network, state,function, cacheSize,clock, uuidSource))
      _ <- router.enroll()
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
  def startRouterWithServerPar[A,M](
                                   config: Config[A],
                                   network: KNetworkBeta[A,KRouterBeta.BroadCastMessage[M]],
                                   function: M => Unit,
                                   cacheSize:Int = 5,
                                   clock: Clock = Clock.systemUTC(),
                                   uuidSource: () => UUID = () => UUID.randomUUID()
                                 ): Task[KRouterBeta[A,M]] = {
    Ref
      .of[Task, NodeRecordIndex[A]](
        getIndex(config, clock)
      )
      .flatMap { state =>
        Task.now(new KRouterBeta(config, network, state,function, cacheSize,clock, uuidSource)).flatMap { router =>
          Task.parMap3(
            router.enroll(),
            router.startServerHandling().startAndForget,
            router.startRefreshCycle().startAndForget
          )((_, _, _) => router)
        }
      }
  }
}
