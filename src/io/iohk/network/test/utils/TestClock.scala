package io.iohk.network.test.utils

import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.duration._

/**
  * Simulates a clock for testing. You can have a static clock (tickOnReturn = false) or a moving clock: it "ticks"
  * after the instant is requested. Simulating the "advancement" of time in defined, fixed steps (defaultTickSize)
  * @param currentTime The current clock time.
  * @param tickOnReturn If the clock should advance after the instant() is requested
  * @param defaultTickSize The default step size for the clock tick.
  */
case class TestClock(
    private var currentTime: Instant = Instant.ofEpochMilli(0),
    private val tickOnReturn: Boolean = false,
    private val defaultTickSize: FiniteDuration = 1.milli
) extends Clock {

  override def withZone(zone: ZoneId): Clock = ???

  override def getZone: ZoneId = ???

  override def instant(): Instant = {
    val now = currentTime
    if (tickOnReturn) tick
    now
  }

  def set(instant: Instant) = currentTime = instant
  def set(millis: Long) = currentTime = Instant.ofEpochMilli(millis)

  def tick: Unit = tick(defaultTickSize)

  def tick(tickSize: FiniteDuration): Unit =
    currentTime = currentTime.plusMillis(tickSize.toMillis)
}
