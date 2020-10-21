package io.iohk.scalanet.discovery.ethereum

import io.iohk.scalanet.discovery.ethereum.codecs.DefaultCodecs
import io.iohk.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import java.net.InetAddress
import org.scalatest._

class EthereumNodeRecordSpec extends FlatSpec with Matchers {
  import DefaultCodecs._
  import EthereumNodeRecord.Keys

  implicit val sigalg = new MockSigAlg()
  val (publicKey, privateKey) = sigalg.newKeyPair

  behavior of "fromNode"

  it should "use the right keys for IPv6 addresses" in {
    val addr = InetAddress.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334")
    val node = Node(publicKey, Node.Address(addr, 30000, 40000))

    val enr = EthereumNodeRecord.fromNode(node, privateKey, seq = 1).require
    Inspectors.forAll(List(Keys.ip6, Keys.tcp6, Keys.udp6)) { k =>
      enr.content.attrs should contain key (k)
    }
    Inspectors.forAll(List(Keys.ip, Keys.tcp, Keys.udp)) { k =>
      enr.content.attrs should not contain key(k)
    }

    val nodeAddress = Node.Address.fromEnr(enr)
    nodeAddress shouldBe Some(node.address)
  }

  it should "use the right keys for IPv4 addresses" in {
    val addr = InetAddress.getByName("127.0.0.1")
    val node = Node(publicKey, Node.Address(addr, 31000, 42000))

    val enr = EthereumNodeRecord.fromNode(node, privateKey, seq = 2).require
    Inspectors.forAll(List(Keys.ip6, Keys.tcp6, Keys.udp6)) { k =>
      enr.content.attrs should not contain key(k)
    }
    Inspectors.forAll(List(Keys.ip, Keys.tcp, Keys.udp)) { k =>
      enr.content.attrs should contain key (k)
    }

    val nodeAddress = Node.Address.fromEnr(enr)
    nodeAddress shouldBe Some(node.address)
  }
}
