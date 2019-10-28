package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock

import org.mockito.Mockito.when
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.mockito.MockitoSugar._
import org.scalatest.prop.GeneratorDrivenPropertyChecks._

import scala.util.Random

class TimeSetSpec extends FlatSpec {

  private val random = new Random()
  private val clock = mock[Clock]

  "touch" should "resort elements by access time" in forAll { s: Set[String] =>
    {
      when(clock.millis()).thenReturn(0)
      val ss: Seq[String] = s.toSeq
      val ts = TimeSet(clock, ss: _*)
      val ssShuffled = random.shuffle(ss)

      val ts2 = ssShuffled.foldLeft(ts) { (acc, next) =>
        val millis = clock.millis()
        when(clock.millis()).thenReturn(millis + 1)
        acc.touch(next)
      }

      ts2.zip(ssShuffled).foreach {
        case (l, r) =>
          l shouldBe r
      }
      ts2.size shouldBe ss.size
    }
  }
}
