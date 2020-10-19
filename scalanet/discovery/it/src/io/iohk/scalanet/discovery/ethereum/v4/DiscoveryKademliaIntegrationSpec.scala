package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.kademlia.KademliaIntegrationSpec
import io.iohk.scalanet.discovery.ethereum.Node
import io.iohk.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import monix.eval.Task
import io.iohk.scalanet.discovery.crypto.SigAlg
import io.iohk.scalanet.NetUtils
import scodec.bits.BitVector
import io.iohk.scalanet.kademlia.XorOrdering
import cats.effect.Resource

class DiscoveryKademliaIntegrationSpec extends KademliaIntegrationSpec("DiscoveryService with StaticUDPPeerGroup") {
  override type PeerRecord = Node

  class DiscoveryTestNode(
      override val self: Node,
      service: DiscoveryService
  ) extends TestNode {
    override def getPeers: Task[Seq[Node]] =
      service.getNodes.map(_.toSeq)
  }

  implicit val sigalg: SigAlg = new MockSigAlg()

  override def generatePeerRecord(): Node = {
    val address = NetUtils.aRandomAddress
    val (publicKey, _) = sigalg.newKeyPair
    Node(publicKey, Node.Address(address.getAddress, address.getPort, address.getPort))
  }

  override def makeXorOrdering(baseId: BitVector): Ordering[Node] =
    XorOrdering[Node](_.id)(baseId)

  override def startNode(
      selfRecord: PeerRecord,
      initialNodes: Set[PeerRecord],
      testConfig: TestNodeKademliaConfig
  ): Resource[Task, TestNode] = ???
}
