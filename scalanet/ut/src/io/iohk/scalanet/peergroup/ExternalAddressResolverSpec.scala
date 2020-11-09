package io.iohk.scalanet.peergroup

import org.scalatest._
import scala.concurrent.duration._
import monix.execution.Scheduler.Implicits.global

class ExternalAddressResolverSpec extends FlatSpec with Matchers {

  behavior of "ExternalAddressResolver"

  it should "resolve the external IP" in {
    val maybeAddress = ExternalAddressResolver.default.resolve.runSyncUnsafe(5.seconds)

    maybeAddress should not be empty
    maybeAddress.get.isLoopbackAddress shouldBe false
  }

  it should "return None if all resolutions fail" in {
    ExternalAddressResolver.checkUrls(List("", "404.html")).runSyncUnsafe(1.seconds) shouldBe empty
  }
}
