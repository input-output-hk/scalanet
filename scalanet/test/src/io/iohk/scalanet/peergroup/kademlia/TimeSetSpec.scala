package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock

import org.scalatest.FlatSpec

import scala.util.Random
import org.scalatest.Matchers._
import org.scalatest.mockito.MockitoSugar._
import org.mockito.Mockito.when
import org.scalatest.prop.GeneratorDrivenPropertyChecks._

class TimeSetSpec extends FlatSpec {

  private val random = new Random()
  private val clock = mock[Clock]

  "touch" should "resort elements by access time" in forAll { s: Set[String] =>
    {
      when(clock.millis()).thenReturn(0)
      val ss: Seq[String] = s.toSeq
      val ts = TimeSet(clock, ss: _*)
      val ssShuffled = random.shuffle(ss)

      ssShuffled.foreach(s => {
        val millis = clock.millis()
        when(clock.millis()).thenReturn(millis + 1)
        ts.touch(s)
      })

      ts.zip(ssShuffled).foreach {
        case (l, r) =>
          l shouldBe r
      }
    }
  }
}
