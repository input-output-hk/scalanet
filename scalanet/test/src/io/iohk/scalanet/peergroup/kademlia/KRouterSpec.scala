package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup
import io.iohk.scalanet.peergroup.InMemoryPeerGroup
import io.iohk.scalanet.peergroup.kademlia.Generators.aRandomBitVector
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.KRouter.Config
import io.iohk.scalanet.peergroup.kademlia.KRouterSpec._
import monix.execution.Scheduler
import org.scalatest.FreeSpec
import org.scalatest.Matchers._
import scodec.bits.BitVector
import org.scalatest.concurrent.ScalaFutures._
import scala.util.Random

class KRouterSpec extends FreeSpec {

  import monix.execution.Scheduler.Implicits.global

  "A single node" - {
    "should locate this node's own id" in {
      val krouter = aKRouter()

      krouter.get(krouter.config.nodeId).futureValue shouldBe krouter.config.nodeRecord
    }

    "should locate any bootstrap nodes" in {
      val (n0, n1) = a2NodeNetwork()

      n1.get(n0.config.nodeId).futureValue shouldBe n0.config.nodeRecord
    }

    "should not locate any other node" in {
      val krouter = aKRouter()
      val someNodeId = aRandomBitVector(krouter.config.nodeId.length.toInt)

      whenReady(krouter.get(someNodeId).failed) { e =>
        e shouldBe an[Exception]
        e.getMessage should startWith(s"Lookup failed for get(${someNodeId.toHex})")
      }
    }

    "should perform a network lookup for nodes it does not know about" in {
      val (_, n1, n2) = a3NodeNetwork(k = 1)

      n1.get(n2.config.nodeId).futureValue shouldBe n2.config.nodeRecord
    }
  }

  "A bootstrap node" - {
    "should locate a new node that contacts it" in {
      // n0, the bootstrap node, knows no nodes initially.
      val (n0, n1) = a2NodeNetwork()

      // assert the bootstrap knows about n1 after n1 contacted it
      n0.get(n1.config.nodeId).futureValue shouldBe n1.config.nodeRecord
    }

    "should inform the new node of its neighbourhood" in {
      val (_, n1, n2) = a3NodeNetwork()
      // n1 and n2 will not know each other
      // because they bootstrap from n0

      // assert that n1 and n2 now know about each other after
      n1.get(n2.config.nodeId).futureValue shouldBe n2.config.nodeRecord
      n2.get(n1.config.nodeId).futureValue shouldBe n1.config.nodeRecord
    }
  }
}

object KRouterSpec {

  val keySizeBits = 160

  val networkSim =
    new peergroup.InMemoryPeerGroup.Network[String, KMessage[String]]()

  def a2NodeNetwork(alpha: Int = 3, k: Int = 20)(implicit scheduler: Scheduler): (KRouter[String], KRouter[String]) = {
    val k1 = aKRouter(Map.empty, alpha, k)
    val k2 = aKRouter(Map(k1.config.nodeId -> k1.config.nodeRecord), alpha, k)
    (k1, k2)
  }

  def a3NodeNetwork(alpha: Int = 3, k: Int = 20)(
      implicit scheduler: Scheduler
  ): (KRouter[String], KRouter[String], KRouter[String]) = {
    val k1 = aKRouter(Map.empty, alpha, k)
    val k2 = aKRouter(Map(k1.config.nodeId -> k1.config.nodeRecord), alpha, k)
    val k3 = aKRouter(Map(k1.config.nodeId -> k1.config.nodeRecord), alpha, k)
    (k1, k2, k3)
  }

  def aKRouter(
      knownPeers: Map[BitVector, String] = Map.empty,
      alpha: Int = 3,
      k: Int = 20
  )(implicit scheduler: Scheduler): KRouter[String] = {

    val underlyingAddress = Random.alphanumeric.take(4).mkString

    val underlyingPeerGroup = new InMemoryPeerGroup[String, KMessage[String]](underlyingAddress)(networkSim)

    import io.iohk.scalanet.TaskValues._
    underlyingPeerGroup.initialize().evaluated

    val knetwork = new KNetworkScalanetImpl[String](underlyingPeerGroup)

    val nodeId = Generators.aRandomBitVector(keySizeBits)
    val config = Config(nodeId, underlyingAddress, knownPeers, alpha, k)

    new KRouter[String](config, knetwork)
  }
}
