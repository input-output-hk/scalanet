package io.iohk.scalanet.kademlia

import java.security.SecureRandom
import cats.effect.Resource
import io.iohk.scalanet.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.{InetMultiAddress, InetPeerGroupUtils}
import monix.eval.Task

import io.iohk.scalanet.peergroup.PeerGroup
import scodec.bits.BitVector

abstract class KRouterKademliaIntegrationSpec(peerGroupName: String)
    extends KademliaIntegrationSpec(s"KRouter and $peerGroupName") {

  override type PeerRecord = NodeRecord[InetMultiAddress]

  override def generatePeerRecord(): PeerRecord = {
    val randomGen = new SecureRandom()
    val testBitLength = 16
    val address = InetMultiAddress(InetPeerGroupUtils.aRandomAddress())
    val id = KBuckets.generateRandomId(testBitLength, randomGen)
    NodeRecord(id, address, address)
  }

  override def makeXorOrdering(baseId: BitVector): Ordering[NodeRecord[InetMultiAddress]] =
    XorNodeOrdering(baseId)

  import io.iohk.scalanet.codec.DefaultCodecs._
  import io.iohk.scalanet.kademlia.codec.DefaultCodecs._
  implicit val codec = implicitly[scodec.Codec[KMessage[InetMultiAddress]]]

  class KRouterTestNode(
      override val self: PeerRecord,
      router: KRouter[InetMultiAddress]
  ) extends TestNode {
    override def getPeers: Task[Seq[NodeRecord[InetMultiAddress]]] = {
      router.nodeRecords.map(_.values.toSeq)
    }
  }

  def makePeerGroup(
      selfRecord: NodeRecord[InetMultiAddress]
  ): Resource[Task, PeerGroup[InetMultiAddress, KMessage[InetMultiAddress]]]

  private def startRouter(
      selfRecord: NodeRecord[InetMultiAddress],
      routerConfig: KRouter.Config[InetMultiAddress]
  ): Resource[Task, KRouter[InetMultiAddress]] = {
    for {
      peerGroup <- makePeerGroup(selfRecord)
      kademliaNetwork = new KNetworkScalanetImpl(peerGroup)
      router <- Resource.liftF(KRouter.startRouterWithServerPar(routerConfig, kademliaNetwork))
    } yield router
  }

  override def startNode(
      selfRecord: PeerRecord,
      initialNodes: Set[PeerRecord],
      testConfig: TestNodeKademliaConfig
  ): Resource[Task, TestNode] = {
    val routerConfig = KRouter.Config(
      selfRecord,
      initialNodes,
      alpha = testConfig.alpha,
      k = testConfig.k,
      serverBufferSize = testConfig.serverBufferSize,
      refreshRate = testConfig.refreshRate
    )
    for {
      router <- startRouter(selfRecord, routerConfig)
    } yield new KRouterTestNode(selfRecord, router)
  }

}

class StaticUDPKRouterKademliaIntegrationSpec extends KRouterKademliaIntegrationSpec("StaticUDP") {
  import io.iohk.scalanet.peergroup.udp.StaticUDPPeerGroup

  override def makePeerGroup(
      selfRecord: NodeRecord[InetMultiAddress]
  ) = {
    val udpConfig = StaticUDPPeerGroup.Config(selfRecord.routingAddress.inetSocketAddress, channelCapacity = 100)
    StaticUDPPeerGroup[KMessage[InetMultiAddress]](udpConfig)
  }
}

class DynamicUDPKRouterKademliaIntegrationSpec extends KRouterKademliaIntegrationSpec("DynamicUDP") {
  import io.iohk.scalanet.peergroup.udp.DynamicUDPPeerGroup

  override def makePeerGroup(
      selfRecord: NodeRecord[InetMultiAddress]
  ) = {
    val udpConfig = DynamicUDPPeerGroup.Config(selfRecord.routingAddress.inetSocketAddress, channelCapacity = 100)
    DynamicUDPPeerGroup[KMessage[InetMultiAddress]](udpConfig)
  }
}
