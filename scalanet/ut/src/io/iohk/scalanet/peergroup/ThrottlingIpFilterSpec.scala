package io.iohk.scalanet.peergroup

import io.iohk.scalanet.peergroup.dynamictls.CustomHandlers.ThrottlingIpFilter
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.IncomingConnectionThrottlingConfig
import io.netty.channel.ChannelHandlerContext
import org.scalatest.FlatSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatestplus.mockito.MockitoSugar.mock

import java.net.InetSocketAddress
import scala.concurrent.duration._

class ThrottlingIpFilterSpec extends FlatSpec with Eventually {
  override implicit val patienceConfig = PatienceConfig(timeout = Span(600, Millis))

  "ThrottlingIpFilter" should "do not accept connection from same ip one after another" in new TestSetup {
    assert(filter.accept(mockContext, randomIp1))
    assert(!filter.accept(mockContext, randomIp1))
  }

  it should "allow connection from different ips" in new TestSetup {
    assert(filter.accept(mockContext, randomIp1))
    assert(filter.accept(mockContext, randomIp2))
  }

  it should "eventually allow connections from same ip" in new TestSetup {
    assert(filter.accept(mockContext, randomIp1))
    assert(!filter.accept(mockContext, randomIp1))
    eventually {
      assert(filter.accept(mockContext, randomIp1))
    }
  }

  it should "allow repeated connections from localhost when configured" in new TestSetup {
    assert(filter.accept(mockContext, localHostAddress))
    assert(filter.accept(mockContext, localHostAddress))
  }

  it should "disallow repeated connections from localhost when configured" in new TestSetup {
    val throttleLocal = defaultConfig.copy(throttleLocalhost = true)
    val filterWithLocalThrottling = new ThrottlingIpFilter(throttleLocal)
    assert(filterWithLocalThrottling.accept(mockContext, localHostAddress))
    assert(!filterWithLocalThrottling.accept(mockContext, localHostAddress))
  }

  it should "eventually allow repeated connections from localhost when throttling is configured" in new TestSetup {
    val throttleLocal = defaultConfig.copy(throttleLocalhost = true)
    val filterWithLocalThrottling = new ThrottlingIpFilter(throttleLocal)
    assert(filterWithLocalThrottling.accept(mockContext, localHostAddress))
    assert(!filterWithLocalThrottling.accept(mockContext, localHostAddress))
    eventually {
      assert(filterWithLocalThrottling.accept(mockContext, localHostAddress))
    }
  }

  trait TestSetup {
    val defaultConfig = IncomingConnectionThrottlingConfig(throttleLocalhost = false, throttlingDuration = 500.millis)

    val mockContext = mock[ChannelHandlerContext]

    val filter = new ThrottlingIpFilter(defaultConfig)

    val randomIp1 = new InetSocketAddress("90.34.1.20", 90)
    val randomIp2 = new InetSocketAddress("90.34.1.21", 90)
    val localHostAddress = new InetSocketAddress("127.0.0.1", 90)
  }

}
