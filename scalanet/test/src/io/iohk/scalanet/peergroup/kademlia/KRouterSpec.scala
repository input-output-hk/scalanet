package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock
import java.util.UUID

import io.iohk.scalanet.peergroup.kademlia.KRouter.{Config, NodeRecord}
import monix.execution.Scheduler
import org.scalatest.FreeSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import KRouterSpec._
import io.iohk.scalanet.peergroup.kademlia.Generators.{aRandomBitVector, aRandomNodeRecord}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.Nodes
import monix.eval.Task
import monix.reactive.Observable
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock

import scala.concurrent.duration._

class KRouterSpec extends FreeSpec {

  implicit val patienceConfig: PatienceConfig =
    PatienceConfig(1 second, 100 millis)

  import monix.execution.Scheduler.Implicits.global

  "A single node" - {
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
  }
}

object KRouterSpec {

  import org.scalatest.mockito.MockitoSugar._
  type SRouter = KRouter[String]
  val knetwork = mock[KNetwork[String]]
  val clock = mock[Clock]
  val uuid = UUID.randomUUID()
  val alpha = 1
  val k = 1

  when(knetwork.kRequests).thenReturn(Observable.empty)

  def aKRouter(
      nodeRecord: NodeRecord[String] = aRandomNodeRecord(),
      knownPeers: Set[NodeRecord[String]] = Set.empty,
      alpha: Int = alpha,
      k: Int = k
  )(implicit scheduler: Scheduler): SRouter = {

    mockEnrollment(nodeRecord, knownPeers, Seq.empty)
    new KRouter(Config(nodeRecord, knownPeers, alpha, k), knetwork, clock, () => uuid)
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
