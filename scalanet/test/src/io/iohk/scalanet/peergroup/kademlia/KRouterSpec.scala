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

import scala.util.Random

class KRouterSpec extends FreeSpec {

  import monix.execution.Scheduler.Implicits.global

  "A single node" - {
    "should locate this node's own id" in {
      val krouter = aKRouter()

      krouter.get(krouter.config.nodeId) shouldBe Some(krouter.config.nodeRecord)
    }

    "should locate any bootstrap nodes" in {
      val bootstrapNodes: Map[BitVector, String] =
        (0 to 3)
          .map(i => aRandomBitVector(keySizeBits) -> s"some-bootstrap-record-$i")
          .toMap

      val krouter = aKRouter(bootstrapNodes)

      bootstrapNodes.foreach {
        case (bootstrapId, bootstrapRecord) =>
          krouter.get(bootstrapId) shouldBe Some(bootstrapRecord)
      }
    }

    "should not locate any other node" in {
      val krouter = aKRouter()
      val someNodeId = aRandomBitVector(krouter.config.nodeId.length.toInt)

      krouter.get(someNodeId) shouldBe None
    }
  }

  "A bootstrap node" - {
    "should locate a new node that contacts it" in {
      val knodes = a2NodeNetwork
      val n0 = knodes(0) // the bootstrap node, which knows no nodes initially.
      val n1 = knodes(1)

      // assert the bootstrap knows about n1 after n1 contacted it
      n0.get(n1.config.nodeId) shouldBe Some(n1.config.nodeRecord)
    }

//    "should inform the new node of its neighbourhood" in {
//      val knodes = a3NodeNetwork
//      val n0 = knodes(0)
//      val n1 = knodes(1) // n1 and n2 will not know each other
//      val n2 = knodes(2) // because they bootstrap from n0
//
//      // assert that n1 and n2 now know about each other after
//      n2.get(n1.config.nodeId) shouldBe Some(n1.config.nodeRecord)
//      n1.get(n2.config.nodeId) shouldBe Some(n2.config.nodeRecord)
//    }
  }

}

object KRouterSpec {

  val keySizeBits = 160

  val networkSim =
    new peergroup.InMemoryPeerGroup.Network[String, KMessage[String]]()

  def a2NodeNetwork(implicit scheduler: Scheduler): Seq[KRouter[String]] = {
    val k1 = aKRouter(Map.empty)
    val k2 = aKRouter(Map(k1.config.nodeId -> k1.config.nodeRecord))
    Seq(k1, k2)
  }

  def a3NodeNetwork(implicit scheduler: Scheduler): Seq[KRouter[String]] = {
    val k1 = aKRouter(Map.empty)
    val k2 = aKRouter(Map(k1.config.nodeId -> k1.config.nodeRecord))
    val k3 = aKRouter(Map(k1.config.nodeId -> k1.config.nodeRecord))
    Seq(k1, k2, k3)
  }

  def aKRouter(
      knownPeers: Map[BitVector, String] = Map.empty
  )(implicit scheduler: Scheduler): KRouter[String] = {

    val peerGroup = new InMemoryPeerGroup[String, KMessage[String]](
      Random.nextString(4)
    )(networkSim)

    import io.iohk.scalanet.TaskValues._
    peerGroup.initialize().evaluated

    val knetwork = new KNetworkScalanetImpl[String](peerGroup)

    val nodeId = Generators.aRandomBitVector(keySizeBits)
    val nodeRecord = Random.nextString(4)
    val config = Config(nodeId, nodeRecord, knownPeers)

    new KRouter[String](config, knetwork)
  }
}
