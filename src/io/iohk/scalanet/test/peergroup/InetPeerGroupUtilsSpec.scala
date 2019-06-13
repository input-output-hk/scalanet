package io.iohk.scalanet.peergroup

import java.net.{Inet4Address, Inet6Address, InetAddress, InetSocketAddress}

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import org.scalatest.Matchers._

import scala.concurrent.duration._
class InetPeerGroupUtilsSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5 seconds)

  behavior of "getChannelId"

  it should "create a channelId for ip6address" in {
    val ipv6Addrr1 = "4B0C:0:0:0:880C:99A8:4B0:4411"
    val ipv6Addrr2 = "4B0C:0:0:0:880C:99A8:4B0:4411"
    val remoteInet6Address = InetAddress.getByName(ipv6Addrr1).asInstanceOf[Inet6Address]
    val localInet6Address = InetAddress.getByName(ipv6Addrr2).asInstanceOf[Inet6Address]
    val remoteAddress = new InetSocketAddress(remoteInet6Address, 11111)
    val localAddress = new InetSocketAddress(localInet6Address, 22222)

    InetPeerGroupUtils.getChannelId(remoteAddress, localAddress).hashCode shouldBe a[Integer]

  }

  it should "create a channelId for ip4address" in {
    val ipv4Addrr1 = "localhost"
    val ipv4Addrr2 = "localhost"

    val remoteInet6Address = InetAddress.getByName(ipv4Addrr1).asInstanceOf[Inet4Address]
    val localInet6Address = InetAddress.getByName(ipv4Addrr2).asInstanceOf[Inet4Address]
    val remoteAddress = new InetSocketAddress(remoteInet6Address, 11111)
    val localAddress = new InetSocketAddress(localInet6Address, 22222)

    InetPeerGroupUtils.getChannelId(remoteAddress, localAddress).hashCode shouldBe a[Integer]

  }

}
