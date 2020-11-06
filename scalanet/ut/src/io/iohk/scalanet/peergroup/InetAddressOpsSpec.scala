package io.iohk.scalanet.peergroup

import java.net.InetAddress

import org.scalatest._

class InetAddressOpsSpec extends FlatSpec with Matchers with Inspectors {
  import InetAddressOps._

  case class TestCase(ip: String, isUnspecified: Boolean = false, isSpecial: Boolean = false, isLAN: Boolean = false)

  val cases = List(
    TestCase("0.0.0.0", isUnspecified = true, isLAN = true),
    TestCase("0.0.0.1", isLAN = true),
    TestCase("0:0:0:0:0:0:0:0", isUnspecified = true),
    TestCase("127.0.0.1", isLAN = true),
    TestCase("::1", isLAN = true),
    TestCase("192.168.1.2", isLAN = true),
    TestCase("192.175.47"),
    TestCase("192.175.48.0", isSpecial = true),
    TestCase("192.175.48.127", isSpecial = true),
    TestCase("192.175.48.255", isSpecial = true),
    TestCase("192.175.49"),
    TestCase("255.255.255.255", isSpecial = true),
    TestCase("2001:4:112::", isSpecial = true),
    TestCase("140.82.121.4")
  )

  behavior of "InetAddressOps"

  it should "correctly calculate each flag" in {
    forAll(cases) {
      case TestCase(ip, isUnspecified, isSpecial, isLAN) =>
        withClue(ip) {
          val addr = InetAddress.getByName(ip)
          withClue("isUnspecified") {
            addr.isUnspecified shouldBe isUnspecified
          }
          withClue("isSpecial") {
            addr.isSpecial shouldBe isSpecial
          }
          withClue("isLAN") {
            addr.isLAN shouldBe isLAN
          }
        }
    }
  }
}
