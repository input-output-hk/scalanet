package io.iohk.scalanet.discovery.ethereum

import org.scalatest._
import java.net.InetAddress
import org.scalatest.prop.TableDrivenPropertyChecks
import io.iohk.scalanet.peergroup.InetMultiAddress
import java.net.InetSocketAddress

class NodeSpec extends FlatSpec with Matchers with TableDrivenPropertyChecks {

  behavior of "Node.Address.checkRelay"

  val cases = Table(
    ("sender", "relayed", "isValid"),
    ("localhost", "localhost", true),
    ("127.0.0.1", "192.168.1.2", true),
    ("127.0.0.1", "140.82.121.4", true),
    ("140.82.121.4", "192.168.1.2", false),
    ("140.82.121.4", "52.206.42.104", true),
    ("140.82.121.4", "0.0.0.0", false),
    ("140.82.121.4", "255.255.255.255", false),
    ("127.0.0.1", "0.0.0.0", false),
    ("127.0.0.1", "192.175.48.127", false)
  )

  it should "correctly calculate the flag for sender-address pairs" in {
    forAll(cases) {
      case (sender, relayed, isValid) =>
        withClue(s"$relayed from $sender") {
          val senderIP = InetAddress.getByName(sender)
          val relayedIP = InetAddress.getByName(relayed)

          Node.Address.checkRelay(sender = senderIP, address = relayedIP) shouldBe isValid
        }
    }
  }

  it should "work on the address instance" in {
    forAll(cases) {
      case (sender, relayed, isValid) =>
        withClue(s"$relayed from $sender") {
          val nodeAddress = Node.Address(InetAddress.getByName(relayed), 30000, 40000)
          val senderMulti = InetMultiAddress(new InetSocketAddress(InetAddress.getByName(sender), 50000))

          nodeAddress.checkRelay(senderMulti) shouldBe isValid
        }
    }
  }
}
