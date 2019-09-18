package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup
import io.iohk.scalanet.peergroup.kademlia.Generators.aRandomBitVector
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.KRouter.{Config, NodeRecord}
import io.iohk.scalanet.peergroup.kademlia.KRouterSpec._
import io.iohk.scalanet.peergroup.{InMemoryPeerGroup, PeerGroup}
import monix.execution.Scheduler
import org.scalatest.FreeSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._

class KRouterSpec extends FreeSpec {

  import monix.execution.Scheduler.Implicits.global

  "A single node" - {
    "should locate this node's own id" in {
      val krouter = aKRouter()

      krouter
        .get(krouter.config.nodeRecord.id)
        .futureValue shouldBe krouter.config.nodeRecord
    }

    "should locate any bootstrap nodes" in {
      val (n0, n1) = a2NodeNetwork()

      n1.get(n0.config.nodeRecord.id).futureValue shouldBe n0.config.nodeRecord
    }

    "should not locate any other node" in {
      val krouter = aKRouter()
      val someNodeId = aRandomBitVector(krouter.config.nodeRecord.id.length.toInt)

      whenReady(krouter.get(someNodeId).failed) { e =>
        e shouldBe an[Exception]
        e.getMessage should startWith(
          s"Lookup failed for get(${someNodeId.toHex})"
        )
      }
    }

    "should perform a network lookup for nodes it does not know about" in {
      val (_, n1, n2) = a3NodeNetwork(k = 1)

      n1.get(n2.config.nodeRecord.id).futureValue shouldBe n2.config.nodeRecord
    }
  }

  "A bootstrap node" - {
    "should locate a new node that contacts it" in {
      // n0, the bootstrap node, knows no nodes initially.
      val (n0, n1) = a2NodeNetwork()

      // assert the bootstrap knows about n1 after n1 contacted it
      n0.get(n1.config.nodeRecord.id).futureValue shouldBe n1.config.nodeRecord
    }

    "should inform the new node of its neighbourhood" in {
      val (_, n1, n2) = a3NodeNetwork()
      // n1 will not know about n2
      // because it starts up before n2 (has enrolled)

      // assert that n1 can discover n2 after n2 has started.
      n1.get(n2.config.nodeRecord.id).futureValue shouldBe n2.config.nodeRecord
      // and vice versa as a sanity check.
      n2.get(n1.config.nodeRecord.id).futureValue shouldBe n1.config.nodeRecord
    }
  }
}

object KRouterSpec {

  type SRouter = KRouter[String]

  val keySizeBits = 160

  val networkSim =
    new peergroup.InMemoryPeerGroup.Network[String, KMessage[String]]()

  val alpha = 1
  val k = 1

  def a2NodeNetwork(alpha: Int = alpha, k: Int = k)(
      implicit scheduler: Scheduler
  ): (SRouter, SRouter) = {
    val k1 = aKRouter(Set.empty, alpha, k)
    val k2 = aKRouter(Set(k1.config.nodeRecord), alpha, k)
    (k1, k2)
  }

  def a3NodeNetwork(alpha: Int = alpha, k: Int = k)(
      implicit scheduler: Scheduler
  ): (SRouter, SRouter, SRouter) = {
    val k1 = aKRouter(Set.empty, alpha, k)
    val k2 = aKRouter(Set(k1.config.nodeRecord), alpha, k)
    val k3 = aKRouter(Set(k1.config.nodeRecord), alpha, k)
    (k1, k2, k3)
  }

  def aKRouter(knownPeers: Set[NodeRecord[String]] = Set.empty, alpha: Int = alpha, k: Int = k)(
      implicit scheduler: Scheduler
  ): SRouter = {

    val nodeRecord = Generators.aRandomNodeRecord()

    val underlyingPeerGroup = PeerGroup.createOrThrow(
      new InMemoryPeerGroup[String, KMessage[String]](nodeRecord.routingAddress)(networkSim),
      nodeRecord
    )

    val knetwork = new KNetworkScalanetImpl(underlyingPeerGroup)

    val config = Config(nodeRecord, knownPeers, alpha, k)
    new KRouter(config, knetwork)
  }
}
