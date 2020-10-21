package io.iohk.scalanet.peergroup

import java.net.InetAddress

import org.scalatest._

class InetAddressOpsSpec extends FlatSpec with Matchers {
  import InetAddressOps._

  behavior of "isUnspecified"

  it should "return true for IPv4 unspecified" in {
    InetAddress.getByName("0.0.0.0").isUnspecified shouldBe true
  }
  it should "return true for IPv6 unspecified" in {
    InetAddress.getByName("0:0:0:0:0:0:0:0").isUnspecified shouldBe true
  }

  behavior of "isSpecial"

  it should "return true for IPv4 specials" in {
    InetAddress.getByName("192.88.99.0").isSpecial shouldBe true
  }
  it should "return true for IPv6 specials" in {
    InetAddress.getByName("2001:4:112::").isSpecial shouldBe true
  }
  it should "return false for loopback" in {
    InetAddress.getByName("127.0.0.1").isSpecial shouldBe false
    InetAddress.getByName("::1").isSpecial shouldBe false
  }

  behavior of "isLAN"

  it should "return true for IPv4 loopback" in {
    InetAddress.getByName("127.0.0.1").isLAN shouldBe true
  }
  it should "return true for IPv6 loopback" in {
    InetAddress.getByName("::1").isLAN shouldBe true
  }
}
