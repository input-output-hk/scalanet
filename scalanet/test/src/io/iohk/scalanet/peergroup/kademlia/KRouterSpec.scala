package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock
import java.util.UUID

import io.iohk.scalanet.peergroup.kademlia.Generators.{aRandomBitVector, aRandomNodeRecord}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.peergroup.kademlia.KRouter.{Config, NodeRecord}
import io.iohk.scalanet.peergroup.kademlia.KRouterSpec._
import monix.eval.Task
import monix.reactive.Observable
import org.mockito.Mockito.{reset, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.FreeSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.mockito.MockitoSugar._
import scodec.bits._
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration._

class KRouterSpec extends FreeSpec {

  implicit val patienceConfig: PatienceConfig =
    PatienceConfig(1 second, 100 millis)

  "A node" - {
    "should locate this node's own id" in {
      val krouter = aKRouter()

      krouter
        .get(krouter.config.nodeRecord.id)
        .futureValue shouldBe krouter.config.nodeRecord
    }

    "should locate any bootstrap nodes" in {
      val bootstrapRecord = aRandomNodeRecord()
      val krouter = aKRouter(knownPeers = Set(bootstrapRecord))

      krouter.get(bootstrapRecord.id).futureValue shouldBe bootstrapRecord
    }

    "should not locate an unknown node - no bootstrap" in {
      val krouter = aKRouter()
      val someNodeId = aRandomBitVector()

      whenReady(krouter.get(someNodeId).failed) { e =>
        e shouldBe an[Exception]
        e.getMessage should startWith(
          s"Lookup failed for get(${someNodeId.toHex})"
        )
      }
    }

    "should not locate an unknown node - with bootstrap" in {
      val bootstrapRecord = aRandomNodeRecord()
      val selfRecord = aRandomNodeRecord()
      val krouter = aKRouter(nodeRecord = selfRecord, knownPeers = Set(bootstrapRecord))
      val someNodeId = aRandomBitVector()

      when(knetwork.findNodes(to = bootstrapRecord, request = FindNodes(uuid, selfRecord, someNodeId)))
        .thenReturn(Task.now(Nodes(uuid, bootstrapRecord, Seq.empty)))

      whenReady(krouter.get(someNodeId).failed) { e =>
        e shouldBe an[Exception]
        e.getMessage should startWith(
          s"Lookup failed for get(${someNodeId.toHex}). Got an exception: java.lang.Exception: Target node id ${someNodeId.toHex} not loaded into kBuckets"
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

      krouter.get(otherNode.id).futureValue shouldBe otherNode
    }

    "should update kbuckets" - {

      val selfRecord = aRandomNodeRecord()
      val otherRecord = aRandomNodeRecord()
      val handler = mock[Option[KResponse[String]] => Task[Unit]]

      "when receiving a PING" in {
        when(handler.apply(Some(Pong(uuid, selfRecord)))).thenReturn(Task.unit)
        when(knetwork.kRequests).thenReturn(Observable((Ping(uuid, otherRecord), handler)))

        val krouter = aKRouter(selfRecord, Set.empty)

        krouter.get(otherRecord.id).futureValue shouldBe otherRecord
      }

      "when receiving a FIND_NODES" in {
        when(handler.apply(Some(Nodes(uuid, selfRecord, Seq())))).thenReturn(Task.unit)
        when(knetwork.kRequests).thenReturn(Observable((FindNodes(uuid, otherRecord, otherRecord.id), handler)))

        val krouter = aKRouter(selfRecord, Set.empty)

        krouter.get(otherRecord.id).futureValue shouldBe otherRecord
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
          krouter.kBuckets.bucket(0) shouldBe TimeSet(bin"0001")
          krouter.kBuckets.bucket(1) shouldBe TimeSet(bin"0010", bin"0011")
          krouter.kBuckets.bucket(2) shouldBe TimeSet(bin"0100", bin"0101", bin"0110", bin"0111")
          krouter.kBuckets.bucket(3) shouldBe TimeSet(
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
          krouter.kBuckets.bucket(0) shouldBe TimeSet(bin"0001")
          krouter.kBuckets.bucket(1) shouldBe TimeSet(bin"0010", bin"0011")
          krouter.kBuckets.bucket(2) shouldBe TimeSet(bin"0101", bin"0110", bin"0111")
          krouter.kBuckets.bucket(3) shouldBe TimeSet(bin"1101", bin"1110", bin"1111")
        })
      }

      "when the bucket is full and the head responsive, that head entry should be moved to the tail and the sender discarded" in {

        setupOrderedPings(selfRecord, knetwork, _ => true)

        val krouter: SRouter = aKRouter(selfRecord, Set.empty, k = 3)

        knetwork.kRequests.doOnComplete(Task {
          krouter.kBuckets.bucket(0) shouldBe TimeSet(bin"0001")
          krouter.kBuckets.bucket(1) shouldBe TimeSet(bin"0010", bin"0011")
          krouter.kBuckets.bucket(2) shouldBe TimeSet(bin"0101", bin"0110", bin"0100")
          krouter.kBuckets.bucket(3) shouldBe TimeSet(bin"1010", bin"1000", bin"1001")
        })
      }
    }
  }
}

object KRouterSpec {

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
  ): SRouter = {

    mockEnrollment(nodeRecord, knownPeers, Seq.empty)
    new KRouter(Config(nodeRecord, knownPeers, alpha, k), knetwork, clock, () => uuid)
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
