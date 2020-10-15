package io.iohk.scalanet.discovery.ethereum

import io.iohk.scalanet.NetUtils.aRandomAddress
import io.iohk.scalanet.discovery.ethereum.codecs.DefaultCodecs
import io.iohk.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import io.iohk.scalanet.discovery.ethereum.v4.DiscoveryNetworkSpec.randomKeyPair
import org.scalatest._

class EthereumNodeRecordSpec extends FlatSpec with Matchers {
  import DefaultCodecs._
  implicit val sigalg = new MockSigAlg()

  behavior of "fromNode"

  it should "survive a roundtrip" in {
    val (publicKey, privateKey) = randomKeyPair
    val address = aRandomAddress
    val node = Node(publicKey, Node.Address(address.getAddress, address.getPort, address.getPort))

    val enr = EthereumNodeRecord.fromNode(node, privateKey, seq = 1).require
    val nodeAddress = Node.Address.fromEnr(enr)

    nodeAddress shouldBe Some(node.address)
  }
}
