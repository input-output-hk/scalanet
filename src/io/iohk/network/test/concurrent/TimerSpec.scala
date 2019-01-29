package io.iohk.network.concurrent

import java.lang.System.currentTimeMillis

import io.iohk.network.utils.concurrent.Timer
import org.scalatest.FlatSpec
import org.scalatest.Matchers._

import scala.concurrent.duration._
import scala.concurrent.{Await, TimeoutException}
import org.scalatest.concurrent.ScalaFutures._

class TimerSpec extends FlatSpec {

  behavior of "Timer"

  it should "run a task after a delay" in {
    val delay = 50.millis
    val t1 = currentTimeMillis()

    Timer
      .schedule(delay) {
        val t2 = currentTimeMillis()
        (t2 - t1).toInt should be >= delay.toMillis.toInt
      }
      .futureValue
  }

  it should "cancel a task" in {
    val delay = 1.second
    val c = Timer
      .schedule(delay) {
        println("Task completed. Oh dear!")
      }

    c.cancel()

    a[TimeoutException] should be thrownBy Await.result(c, delay * 2)
  }
}
