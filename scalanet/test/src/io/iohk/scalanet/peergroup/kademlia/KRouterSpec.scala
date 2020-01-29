package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock
import java.util.UUID

import cats.effect.concurrent.Ref
import io.iohk.scalanet.codec.{StreamCodecFromContract, StringCodecContract}
import io.iohk.scalanet.peergroup.kademlia.Generators.{aRandomBitVector, aRandomNodeRecord}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.{KRequest, KResponse}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.peergroup.kademlia.KRouter.{Config, NodeRecord}
import io.iohk.scalanet.peergroup.kademlia.KRouterSpec.KNetworkScalanetInternalTestImpl.{KNetworkScalanetInternalTestImpl, NodeData}
import io.iohk.scalanet.peergroup.kademlia.KRouterSpec._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.mockito.Mockito.{reset, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.FreeSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.mockito.MockitoSugar._
import scodec.bits._

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

class KRouterSpec extends FreeSpec with Eventually {

  implicit val scheduler = Scheduler.fixedPool("test", 16)

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(1 second, 100 millis)

  "A node" - {
    "should locate this node's own id" in {
      val krouter = aKRouter()

      krouter
        .get(krouter.config.nodeRecord.id)
        .runSyncUnsafe() shouldBe krouter.config.nodeRecord
    }

    "should locate any bootstrap nodes" in {
      val bootstrapRecord = aRandomNodeRecord()
      val krouter = aKRouter(knownPeers = Set(bootstrapRecord))

      krouter.get(bootstrapRecord.id).runSyncUnsafe() shouldBe bootstrapRecord
    }

    "should not locate an unknown node - no bootstrap" in {
      val krouter = aKRouter()
      val someNodeId = aRandomBitVector(264)
      System.out.println("someNodeID: " + someNodeId)

      whenReady(krouter.get(someNodeId).runToFuture.failed) { e =>
        e shouldBe an[Exception]

        e.getMessage should startWith(
          s"Target node id ${someNodeId.toHex} not found"
        )
      }
    }

    "should not locate an unknown node - with bootstrap" in {
      val bootstrapRecord = aRandomNodeRecord()
      val selfRecord = aRandomNodeRecord()
      val krouter = aKRouter(nodeRecord = selfRecord, knownPeers = Set(bootstrapRecord))
      val someNodeId = aRandomBitVector(264)

      when(knetwork.findNodes(to = bootstrapRecord, request = FindNodes(uuid, selfRecord, someNodeId)))
        .thenReturn(Task.now(Nodes(uuid, bootstrapRecord, Seq.empty)))

      whenReady(krouter.get(someNodeId).runToFuture.failed) { e =>
        e shouldBe an[Exception]
        e.getMessage should startWith(
          s"Target node id ${someNodeId.toHex} not found"
        )
      }
    }

    "should perform a network lookup for nodes it does not know about" in {
      val selfRecord = aRandomNodeRecord()
      val bootstrapRecord = aRandomNodeRecord()
      val otherNode = aRandomNodeRecord()

      val krouter = aKRouter(selfRecord, Set(bootstrapRecord))
      val nodesResponse = Nodes(uuid, bootstrapRecord, Seq(otherNode))

      when(knetwork.findNodes(to = bootstrapRecord, request = FindNodes(uuid, selfRecord, otherNode.id)))
        .thenReturn(Task.now(nodesResponse))

      // Nodes are only considered found if they are online, i.e they respond to query
      when(knetwork.findNodes(to = otherNode, request = FindNodes(uuid, selfRecord, otherNode.id)))
        .thenReturn(Task.now(Nodes(uuid, otherNode, Seq())))

      // nodes are added in background, so to avoid flaky test eventually is needed
      eventually {
        krouter.get(otherNode.id).runSyncUnsafe() shouldBe otherNode
      }
    }

    "should update kbuckets" - {

      val selfRecord = aRandomNodeRecord()
      val otherRecord = aRandomNodeRecord()
      val handler = mock[Option[KResponse[String]] => Task[Unit]]

      "when receiving a PING" in {
        when(handler.apply(Some(Pong(uuid, selfRecord)))).thenReturn(Task.unit)
        when(knetwork.kRequests).thenReturn(Observable((Ping(uuid, otherRecord), handler)))

        val krouter = aKRouter(selfRecord, Set.empty)

        eventually {
          krouter.get(otherRecord.id).runSyncUnsafe() shouldBe otherRecord
        }
      }

      "when receiving a FIND_NODES" in {
        when(handler.apply(Some(Nodes(uuid, selfRecord, Seq(selfRecord))))).thenReturn(Task.unit)
        when(knetwork.kRequests).thenReturn(Observable((FindNodes(uuid, otherRecord, otherRecord.id), handler)))

        val krouter = aKRouter(selfRecord, Set.empty)

        eventually {
          krouter.get(otherRecord.id).runSyncUnsafe() shouldBe otherRecord
        }
      }
    }

    "handling scenarios for eviction logic" - {

      // This test constructs a situation where a node with id=0 is pinged by an exhaustive set of 4-bit node ids.
      // This choice of node id means that node id and distance are equal.
      // By generating (and sending pings from) and ensuring k>=8, we are thus able to assert a kbucket state as follows:
      // KBuckets(baseId = 0):
      //	bucket 0: (id=0001, d=1)
      //	bucket 1: (id=0010, d=2), (id=0011, d=3)
      //	bucket 2: (id=0100, d=4), (id=0101, d=5), (id=0110, d=6), (id=0111, d=7)
      //	bucket 3: (id=1000, d=8), (id=1001, d=9), (id=1010, d=10), (id=1011, d=11), (id=1100, d=12), (id=1101, d=13), (id=1110, d=14), (id=1111, d=15)

      // We subsequently set k=3 (to activate eviction logic) and simulate that nodes are unresponsive if their ids
      // are in either {0100} or {1000, 1001, 1010, 1011, 1100}.
      // It should then be the case that the unresponsive nodes are evicted and the bucket state corresponds to
      // bucket 0: (id=0001, d=1)
      // bucket 1: (id=0010, d=2), (id=0011, d=3)
      // bucket 2: (id=0101, d=5), (id=0110, d=6), (id=0111, d=7)
      // bucket 3: (id=1101, d=13), (id=1110, d=14), (id=1111, d=15)

      // We subsequently simulate that the above unresponsive nodes are now responsive (whilst still holding k = 3).
      // This then leads to an expected kbucket state of
      // bucket 0: (id=0001, d=1)
      // bucket 1: (id=0010, d=2), (id=0011, d=3)
      // bucket 2: (id=0101, d=5), (id=0110, d=6), (id=0100, d=4)
      // bucket 3: (id=1010, d=10), (id=1000, d=8), (id=1001, d=9) (because the head, 1000 is moved to the tail and 1011 discarded,

      // The explanation of this state is as follows:
      // For bucket 2, {0100, 0101, 0110} will be added as normal then a ping is received from 0111.
      // This results in the head, 0100 being moved to the tail and 0111 being discarded to give {0101, 0110, 0100}.

      // re bucket 3, {1000, 1001, 1010} will be added as normal then pings will be received from {1011, 1100, 1101, 1110, 1111}.
      // This results in the following sequence of actions
      // the head 1000 is moved to the tail and 1011 discarded to give {1001, 1010, 1000}
      // the head 1001 is moved to the tail and 1100 discarded to give {1010, 1000, 1001}
      // the head 1010  "   "    "  "   "    "  1101    "       "  "   {1000, 1001, 1010}
      // the head 1000  "   "    "  "   "    "  1110    "       "  "   {1001, 1010, 1000}
      // the head 1001  "   "    "  "   "    "  1111    "       "  "   {1010, 1000, 1001}

      val selfRecord = aRandomNodeRecord(bitLength = 4).copy(id = bin"0000")

      "when a bucket has fewer than k entries, node ids should be added to the tail of the bucket" in {

        setupOrderedPings(selfRecord, knetwork, _ => true)

        val krouter: SRouter = aKRouter(selfRecord, Set.empty, k = 8)

        knetwork.kRequests.doOnComplete(Task {
          krouter.kBuckets.runSyncUnsafe().buckets(0) shouldBe TimeSet(bin"0001")
          krouter.kBuckets.runSyncUnsafe().buckets(1) shouldBe TimeSet(bin"0010", bin"0011")
          krouter.kBuckets.runSyncUnsafe().buckets(2) shouldBe TimeSet(bin"0100", bin"0101", bin"0110", bin"0111")
          krouter.kBuckets.runSyncUnsafe().buckets(3) shouldBe TimeSet(
            bin"1000",
            bin"1001",
            bin"1010",
            bin"1011",
            bin"1100",
            bin"1101",
            bin"1110",
            bin"1111"
          )
        })
      }

      "when a bucket is full and the head unresponsive, that head entry should be evicted and sender inserted at the tail " in {

        val responsivePredicate: NodeRecord[String] => Boolean = n =>
          !Set(bin"0100").contains(n.id) &&
            !Set(bin"1000", bin"1001", bin"1010", bin"1011", bin"1100").contains(n.id)

        setupOrderedPings(selfRecord, knetwork, responsivePredicate)

        val krouter: SRouter = aKRouter(selfRecord, Set.empty, k = 3)

        knetwork.kRequests.doOnComplete(Task {
          krouter.kBuckets.runSyncUnsafe().buckets(0) shouldBe TimeSet(bin"0001")
          krouter.kBuckets.runSyncUnsafe().buckets(1) shouldBe TimeSet(bin"0010", bin"0011")
          krouter.kBuckets.runSyncUnsafe().buckets(2) shouldBe TimeSet(bin"0101", bin"0110", bin"0111")
          krouter.kBuckets.runSyncUnsafe().buckets(3) shouldBe TimeSet(bin"1101", bin"1110", bin"1111")
        })
      }

      "when the bucket is full and the head responsive, that head entry should be moved to the tail and the sender discarded" in {

        setupOrderedPings(selfRecord, knetwork, _ => true)

        val krouter: SRouter = aKRouter(selfRecord, Set.empty, k = 3)

        knetwork.kRequests.doOnComplete(Task {
          krouter.kBuckets.runSyncUnsafe().buckets(0) shouldBe TimeSet(bin"0001")
          krouter.kBuckets.runSyncUnsafe().buckets(1) shouldBe TimeSet(bin"0010", bin"0011")
          krouter.kBuckets.runSyncUnsafe().buckets(2) shouldBe TimeSet(bin"0101", bin"0110", bin"0100")
          krouter.kBuckets.runSyncUnsafe().buckets(3) shouldBe TimeSet(bin"1010", bin"1000", bin"1001")
        })
      }
    }

    "should do proper initial lookup" - {
      "when starting with one bootstrap node without neighbours" in {
        val initialKnownNode = NodeData.getBootStrapNode(0)
        val testRouter =
          createTestRouter(peerConfig = Map.empty + (initialKnownNode.myData.id -> initialKnownNode)).runSyncUnsafe()

        testRouter.nodeRecords.runSyncUnsafe().size shouldEqual 2
        testRouter.get(initialKnownNode.id).runSyncUnsafe() shouldBe initialKnownNode.myData
      }

      "when starting with 4 bootstrap nodes without neighbours" in {
        val initialKnownNode = NodeData.getBootStrapNode(0)
        val initialKnownNode1 = NodeData.getBootStrapNode(0)
        val initialKnownNode2 = NodeData.getBootStrapNode(0)
        val initialKnownNode3 = NodeData.getBootStrapNode(0)
        val initialNodes = Seq(initialKnownNode, initialKnownNode1, initialKnownNode2, initialKnownNode3)

        val testRouter =
          createTestRouter(
            peerConfig = Map.empty ++ Seq(
              initialKnownNode.myData.id -> initialKnownNode,
              initialKnownNode1.myData.id -> initialKnownNode1,
              initialKnownNode2.myData.id -> initialKnownNode2,
              initialKnownNode3.myData.id -> initialKnownNode3
            )
          ).runSyncUnsafe()

        testRouter.nodeRecords.runSyncUnsafe().size shouldEqual 5
        initialNodes.foreach(nodeData => testRouter.get(nodeData.id).runSyncUnsafe() shouldBe nodeData.myData)
      }

      "when starting with one bootstrap node with 6 online neighbours" in {
        val initialKnownNode = NodeData.getBootStrapNode(6)
        val onlineNeighbours = initialKnownNode.neigbours
        val mapWithBootStrap = Map.empty + (initialKnownNode.id -> initialKnownNode)
        val mapWithOnlineNeighbours =
          onlineNeighbours.foldLeft(mapWithBootStrap)((map, node) => map + (node.id -> node))

        val testRouter =
          createTestRouter(peerConfig = mapWithOnlineNeighbours).runSyncUnsafe()

        // 1 bootstrap + myself + 6 new online nodes
        eventually {
          testRouter.nodeRecords.runSyncUnsafe().size shouldEqual 8
        }
        onlineNeighbours.foreach { node =>
          testRouter.get(node.id).runSyncUnsafe() shouldBe node.myData
        }
      }

      "when starting with three bootstraps, two with 3 online neighbours and one with 3 offline" in {
        val initialKnownNode = NodeData.getBootStrapNode(3)
        val initialKnownNode1 = NodeData.getBootStrapNode(3)
        val initialKnownNode2 = NodeData.getBootStrapNode(3)

        val onlineNeighbours = initialKnownNode.neigbours ++ initialKnownNode1.neigbours
        val mapWithBootStrap = Map.empty ++ Seq(
          (initialKnownNode.id -> initialKnownNode),
          (initialKnownNode1.id -> initialKnownNode1),
          (initialKnownNode2.id -> initialKnownNode2)
        )
        val mapWithOnlineNeighbours =
          onlineNeighbours.foldLeft(mapWithBootStrap)((map, node) => map + (node.id -> node))

        val testRouter =
          createTestRouter(peerConfig = mapWithOnlineNeighbours).runSyncUnsafe()

        // 3 bootstrap + myself + 6 new online nodes
        eventually {
          testRouter.nodeRecords.runSyncUnsafe().size shouldEqual 10
        }
        onlineNeighbours.foreach { node =>
          testRouter.get(node.id).runSyncUnsafe() shouldBe node.myData
        }
      }

      "when starting with one bootstrap node with 3 online neighbours with one neighbours having closer available nodes" in {

        /**
          * Toplogy in test:
          *                 Neighbour -> 5 Far Neighbours
          *
          * BootstapNode -> Neighbour -> 5 Far Neighbours
          *
          *                 Neighbour -> 10 Middle distance Neighbours -> 10 Closet Neighbours
          *
          * All Middle and closest neigbours should be identified. Not all far away neighbours will be identified as lookup
          * will finish after receiving responses from k closest nodes
          */
        val initiator = aRandomNodeRecord()
        val xorOrder = new XorNodeOrdering[String](initiator.id)

        // 30 notKnownNodes + 1 bootstrap + 3 bootstrap neighbours
        val allNodes = (0 until 34)
          .map(_ => NodeData(Seq(), aRandomNodeRecord(), bootstrap = false))
          .sortBy(nodedata => nodedata.myData)(xorOrder)

        val (onlineNodeToFind, initialSetup) = allNodes.splitAt(30)

        val initialKnownNode = NodeData(initialSetup.take(3), initialSetup.last.myData, true)

        val onlineNeighbours = initialKnownNode.neigbours

        val (closestNodes, rest) = onlineNodeToFind.splitAt(10)

        val (secondClosest, rest1) = rest.splitAt(10)

        val updatedHead = secondClosest(0).copy(neigbours = closestNodes)

        val neighbour0Neighbours = secondClosest.updated(0, updatedHead)

        val (neighbour1Neighbours, neighbour2Neighbours) = rest1.splitAt(5)

        val neigbour0 = onlineNeighbours(0).copy(neigbours = neighbour0Neighbours)
        val neigbour1 = onlineNeighbours(1).copy(neigbours = neighbour1Neighbours)
        val neigbour2 = onlineNeighbours(2).copy(neigbours = neighbour2Neighbours)

        val onlineTopology = Seq(neigbour0, neigbour1, neigbour2) ++ neighbour0Neighbours ++ closestNodes ++ neighbour1Neighbours ++ neighbour2Neighbours

        val mapWithBootStrap = Map.empty + (initialKnownNode.myData.id -> initialKnownNode)
        val mapWithOnlineNeighbours =
          onlineTopology.foldLeft(mapWithBootStrap)((map, node) => map + (node.id -> node))

        val testRouter =
          createTestRouter(nodeRecord = initiator, peerConfig = mapWithOnlineNeighbours).runSyncUnsafe()

        // all closest nodes should be identified and added to table after succesfull lookup
        (closestNodes).foreach { node =>
          testRouter.get(node.id).runSyncUnsafe() shouldBe node.myData
        }

        // all middle closest nodes should be identified and added to table after succesfull lookup
        (secondClosest).foreach { node =>
          testRouter.get(node.id).runSyncUnsafe() shouldBe node.myData
        }
      }
    }

    "should refresh buckets periodically" - {
      "when known node have met new node" in {
        val selfNode = aRandomNodeRecord()
        val initialKnownNode = NodeData.getBootStrapNode(0)
        val testRefreshRate = 3.seconds

        val intialMap = Map(
          initialKnownNode.id -> initialKnownNode
        )

        val newNode = NodeData(Seq(), aRandomNodeRecord(), bootstrap = false)

        (for {
          testState <- Ref.of[Task, Map[BitVector, NodeData[String]]](intialMap)
          network = new KNetworkScalanetInternalTestImpl(testState)
          router <- KRouter.startRouterWithServerPar(
            Config(selfNode, Set(initialKnownNode.myData), refreshRate = testRefreshRate),
            network,
            clock,
            () => uuid
          )(new StreamCodecFromContract[String](StringCodecContract))
          // Just after enrollment there will be only one bootstrap node without neighbours
          nodesAfterEnroll <- router.nodeRecords
          // Simulate situation that initial known node learned about new node
          _ <- KNetworkScalanetInternalTestImpl.addNeighbours(testState, Seq(newNode), initialKnownNode.id)
        } yield {
          nodesAfterEnroll.size shouldEqual 2

          eventually {
            router.nodeRecords.runToFuture.futureValue.get(newNode.id) shouldEqual Some(newNode.myData)
          }(config = PatienceConfig(testRefreshRate + 1.second, 200 millis), org.scalactic.source.Position.here)
        }).runSyncUnsafe()
      }
    }
  }
}

object KRouterSpec {
  object KNetworkScalanetInternalTestImpl {
    case class NodeData[A](neigbours: Seq[NodeData[A]], myData: NodeRecord[A], bootstrap: Boolean) {
      def id: BitVector = myData.id
    }

    object NodeData {
      def getBootStrapNode(
          numberOfNeighbours: Int,
          bootStrapRecord: NodeRecord[String] = aRandomNodeRecord()
      ): NodeData[String] = {
        val neighbours =
          (0 until numberOfNeighbours)
            .map(_ => aRandomNodeRecord())
            .map(record => NodeData(Seq(), record, bootstrap = false))
        NodeData(neighbours, bootStrapRecord, bootstrap = true)
      }
    }

    class KNetworkScalanetInternalTestImpl[A](val nodes: Ref[Task, Map[BitVector, NodeData[A]]]) extends KNetwork[A] {
      override def findNodes(to: NodeRecord[A], request: FindNodes[A]): Task[Nodes[A]] = {
        for {
          currentState <- nodes.get
          response <- currentState.get(to.id) match {
            case Some(value) =>
              Task.now(Nodes(request.requestId, value.myData, value.neigbours.map(_.myData)))
            case None =>
              Task.raiseError(new TimeoutException(s"Task timed-out after of inactivity"))
          }
        } yield response
      }

      override def ping(to: NodeRecord[A], request: Ping[A]): Task[Pong[A]] = {
        for {
          currentState <- nodes.get
          response <- currentState.get(to.id) match {
            case Some(value) =>
              Task.now(Pong(request.requestId, value.myData))
            case None =>
              Task.raiseError(new TimeoutException(s"Task timed-out after of inactivity"))
          }
        } yield response
      }

      // No server request handling for now
      override def kRequests: Observable[(KRequest[A], Option[KResponse[A]] => Task[Unit])] = Observable.empty
    }

    def addNeighbours[A](
        currentState: Ref[Task, Map[BitVector, NodeData[A]]],
        newNeighbours: Seq[NodeData[A]],
        nodeToUpdate: BitVector
    ): Task[Unit] = {
      for {
        _ <- currentState.update { s =>
          val withNeighbours = newNeighbours.foldLeft(s)((state, neighbour) => state + (neighbour.id -> neighbour))
          withNeighbours.updated(nodeToUpdate, withNeighbours(nodeToUpdate).copy(neigbours = newNeighbours))
        }
      } yield ()
    }

  }
  def createTestRouter(
      nodeRecord: NodeRecord[String] = aRandomNodeRecord(),
      peerConfig: Map[BitVector, NodeData[String]]
  )(implicit scheduler: Scheduler): Task[KRouter[String]] = {

    val knownPeers = peerConfig.collect {
      case (_, data) if data.bootstrap => data.myData
    }.toSet

    for {
      testState <- Ref.of[Task, Map[BitVector, NodeData[String]]](peerConfig)
      network = new KNetworkScalanetInternalTestImpl(testState)
      router <- KRouter.startRouterWithServerPar(Config(nodeRecord, knownPeers), network, clock, () => uuid)(new StreamCodecFromContract[String](StringCodecContract))
    } yield router
  }

  type SRouter = KRouter[String]
  val knetwork = mock[KNetwork[String]]
  val clock = mock[Clock]
  val uuid = UUID.randomUUID()
  val alpha = 1
  val k = 4000

  when(knetwork.kRequests).thenReturn(Observable.empty)

  def aKRouter(
      nodeRecord: NodeRecord[String] = aRandomNodeRecord(),
      knownPeers: Set[NodeRecord[String]] = Set.empty,
      alpha: Int = alpha,
      k: Int = k
  )(implicit scheduler: Scheduler): SRouter = {

    mockEnrollment(nodeRecord, knownPeers, Seq.empty)
    KRouter
      .startRouterWithServerSeq(Config(nodeRecord, knownPeers, alpha, k), knetwork, clock, () => uuid)(new StreamCodecFromContract[String](StringCodecContract))
      .runSyncUnsafe()
  }

  private def setupOrderedPings(
      selfRecord: NodeRecord[String],
      network: KNetwork[String],
      responsivePredicate: NodeRecord[String] => Boolean
  ): Unit = {
    import org.mockito.ArgumentMatchers.any

    reset(knetwork)
    val bitLength: Int = selfRecord.id.size.toInt
    val ids = Generators.genBitVectorExhaustive(bitLength).filterNot(_ == selfRecord.id)
    val handler = mock[Option[KResponse[String]] => Task[Unit]]
    when(handler.apply(Some(Pong(uuid, selfRecord)))).thenReturn(Task.unit)

    when(knetwork.ping(any(), any())).thenAnswer((invocation: InvocationOnMock) => {
      val to = invocation.getArgument(0).asInstanceOf[NodeRecord[String]]
      if (responsivePredicate(to))
        Task.now(Pong(uuid, to))
      else
        Task.raiseError(new Exception("Donnae want this one"))
    })
    val kRequests =
      Observable.fromIterable(ids.map(id => (Ping(uuid, aRandomNodeRecord(bitLength).copy(id = id)), handler)))

    when(knetwork.kRequests).thenReturn(kRequests)
  }

  private def mockEnrollment(
      nodeRecord: NodeRecord[String],
      knownPeers: Set[NodeRecord[String]],
      otherNodes: Seq[NodeRecord[String]]
  ): Unit = {
    import org.mockito.ArgumentMatchers.{eq => meq}

    when(knetwork.findNodes(anyOf(knownPeers), meq(FindNodes(uuid, nodeRecord, nodeRecord.id))))
      .thenAnswer((invocation: InvocationOnMock) => {
        val to = invocation.getArgument(0).asInstanceOf[NodeRecord[String]]
        Task.now(Nodes(uuid, to, otherNodes))
      })
  }

  private def anyOf[T](s: Set[T]): T = {
    import org.mockito.ArgumentMatchers.argThat
    argThat(s.contains)
  }
}
