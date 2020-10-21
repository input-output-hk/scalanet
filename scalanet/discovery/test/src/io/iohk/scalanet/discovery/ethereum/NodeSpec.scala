package io.iohk.scalanet.discovery.ethereum

import org.scalatest._
import java.net.InetAddress

class NodeSpec extends FlatSpec with Matchers {

  behavior of "Node.Address.checkRelay"

  val localhost = InetAddress.getByName("127.0.0.1")

  it should "accept local IPs for local senders" in {
    Node.Address.checkRelay(localhost, localhost) shouldBe true
  }

  it should "not accept local IPs from non-local senders" in {
    Node.Address.checkRelay(address = localhost, sender = InetAddress.getByName("140.82.121.4")) shouldBe false
  }
}
