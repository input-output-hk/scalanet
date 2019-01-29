package io.iohk.network.telemetry.test

import java.util.concurrent.TimeUnit

import io.iohk.network.telemetry.ScalaTimer
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.{FlatSpec, MustMatchers}

class ScalaTimerSpec extends FlatSpec with MustMatchers {

  behavior of "ScalaTimer"

  it should "wrap a block returning Unit" in {
    val simpleRegistry = new SimpleMeterRegistry()
    val timer = Timer
      .builder("test timer")
      .register(simpleRegistry)
    import ScalaTimer._
    timer.asScala.wrap {
      Thread.sleep(20)
    }
    timer.totalTime(TimeUnit.MILLISECONDS) must be > (20.0)
  }
  it should "wrap a block returning a value" in {
    val simpleRegistry = new SimpleMeterRegistry()
    val timer = Timer
      .builder("test timer")
      .register(simpleRegistry)
    import ScalaTimer._
    val result = timer.asScala.wrap {
      Thread.sleep(20)
      1
    }
    result mustBe 1
    timer.totalTime(TimeUnit.MILLISECONDS) must be > (20.0)
  }
}
