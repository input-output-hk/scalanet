package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress

import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.prop.Tables.Table

import InetPeerGroupUtils.getChannelId

class InetPeerGroupUtilsSpec extends FlatSpec {

  behavior of "getChannelId"

  val values = Table(
    ("address0", "address1"),
    (addr("4B0C:0:0:0:880C:99A8:4B0:4411", 1111), addr("4B0D:0:0:0:880C:99A8:4B0:4411", 2222)),
    (addr("2001:0db8:85a3:0000:0000:8a2e:0370:7334", 1111), addr("2002:0db8:85a3:0000:0000:8a2e:0370:7334", 2222)),
    (addr("::1", 128), addr("::1", 128)),
    (addr("localhost", 1111), addr("localhost", 2222)),
    (addr("127.0.0.1", 1111), addr("127.0.0.1", 2222)),
    (addr("127.0.0.0", 0), addr("127.0.0.0", 0))
  )

  it should "create a channelId for IPv6 and IPv4 6addresses" in {
    forAll(values) { (address0, address1) =>
      getChannelId(address0, address1) should be(address0, address1)
    }
  }

  it should "provide distinct channel ids for distinct address pairs" in {
    forAll(values) { (address00, address01) =>
      forAll(values) { (address10, address11) =>
        if ((address00, address01) != (address10, address11))
          getChannelId(address00, address01) should not be getChannelId(address10, address11)
      }
    }
  }

  private def addr(host: String, port: Int) = new InetSocketAddress(host, port)
}
