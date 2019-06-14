package io.iohk.scalanet.peergroup

import java.net.{InetAddress, InetSocketAddress}

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import org.scalatest.Matchers._
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.prop.Tables.Table
import scala.concurrent.duration._
class InetPeerGroupUtilsSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5 seconds)

  behavior of "getChannelId"

  val values = Table(
    ("ipAddress1", "ipAddress2", "port1", "port2"),
    ("4B0C:0:0:0:880C:99A8:4B0:4411", "4B0D:0:0:0:880C:99A8:4B0:4411", 1111, 222),
    ("2001:0db8:85a3:0000:0000:8a2e:0370:7334", "2002:0db8:85a3:0000:0000:8a2e:0370:7334", 1111, 222),
    ("::1", "::1", 128, 128),
    ("localhost", "localhost", 1111, 2222),
    ("127.0.0.1", "127.0.0.1", 1111, 2222),
    ("127.0.0.0", "127.0.0.0", 0, 0)
  )

  it should "create a channelId for all ip6address and ip4Address" in {

    TableDrivenPropertyChecks.forAll(values) { (ipAddress1, ipAddress2, port1, port2) =>
      val remoteInet6Address = InetAddress.getByName(ipAddress1)
      val localInet6Address = InetAddress.getByName(ipAddress2)
      val remoteAddress = new InetSocketAddress(remoteInet6Address, port1)
      val localAddress = new InetSocketAddress(localInet6Address, port2)

      InetPeerGroupUtils.getChannelId(remoteAddress, localAddress).hashCode shouldBe a[Integer]

    }

  }

}
