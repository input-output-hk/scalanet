package io.iohk.network

import java.time.Clock

import io.iohk.network.test.utils.TestClock
import io.iohk.network.utils.FiniteSizedMap
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FlatSpec, MustMatchers}

import scala.concurrent.duration._

class FiniteSizedMapSpec extends FlatSpec with PropertyChecks with MustMatchers {

  "A FiniteSizedMap" should "retrieve keys that were added" in {
    forAll { (s1: String, s2: String, expMilli: Int) =>
      whenever(s1 != s2 && expMilli >= 0) {
        val map = new FiniteSizedMap[String, Int](1, expMilli milli, Clock.systemUTC())
        map.size mustBe 0
        map.put(s1, 1)
        map.size mustBe 1
        map.get(s1) mustBe Some(1)
        map.get(s2) mustBe None
      }
    }
  }

  it should "be empty when created" in {
    val map = new FiniteSizedMap[String, Int](1, 10 milli, Clock.systemUTC())
    map.size mustBe 0
    map.values.size mustBe 0
  }

  it should "drop values already expired" in {
    val expiration = 1 milli
    val mockClock = new TestClock(defaultTickSize = expiration, tickOnReturn = false)
    forAll { (values: Map[String, Int]) =>
      {
        whenever(values.nonEmpty) {
          val map = new FiniteSizedMap[String, Int](values.size, expiration, mockClock)
          values.foreach(value => map.put(value._1, value._2))
          map.size mustBe values.size
          values.foreach {
            case (key, value) => map.get(key) mustBe Some(value)
          }
          map.dropExpired.size mustBe 0
          map.size mustBe values.size
          mockClock.tick // clock time equal to expiration time
          map.dropExpired.size mustBe 0
          map.size mustBe values.size
          mockClock.tick // clock time ahead of expiration time
          map.dropExpired.toMap mustBe values
          map.size mustBe 0
        }
      }
    }
  }

  it should "disregard keys when full and none has expired" in {
    val expiration = 1 milli
    val mockClock = new TestClock(defaultTickSize = expiration, tickOnReturn = false)
    forAll { (values: Map[String, Int]) =>
      {
        whenever(values.size > 1) {
          val map = new FiniteSizedMap[String, Int](values.size - 1, expiration, mockClock)
          val lastToAdd = values.head
          values.tail.foreach(value => map += ((value._1, value._2)))
          map.size mustBe values.tail.size
          map.get(lastToAdd._1) mustBe None
          map += lastToAdd
          map.size mustBe values.tail.size
          map.get(lastToAdd._1) mustBe None
        }
      }
    }
  }

  it should "replace keys when full and there is at least one that has expired" in {
    val expiration = 1 milli
    val mockClock = new TestClock(defaultTickSize = expiration, tickOnReturn = false)
    forAll { (values: Map[String, Int]) =>
      {
        whenever(values.size > 1) {
          val map = new FiniteSizedMap[String, Int](values.size - 1, expiration, mockClock)
          val lastToAdd = values.head
          values.tail.foreach(value => map += ((value._1, value._2)))
          map.size mustBe values.tail.size
          map.get(lastToAdd._1) mustBe None
          mockClock.tick(expiration * 2)
          map += lastToAdd
          map.size mustBe values.tail.size
          map.get(lastToAdd._1) mustBe Some(lastToAdd._2)
          map.get(values.tail.head._1) mustBe None
        }
      }
    }
  }
}
