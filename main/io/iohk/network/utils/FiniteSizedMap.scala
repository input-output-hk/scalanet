package io.iohk.network.utils

import java.time.{Clock, Instant}
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
  * A map that has a limited size and the concept of expiration.
  * Once the max size has been reached, new pairs can be added if any of the existing pairs is expired.
  * This will be used to control the amount of nodes discovery is querying at the same time. It works two ways:
  * prevents Discovery from waiting for a significant amount of nodes, and prevents waiting for dead nodes
  * @param maxSize
  * @param expiration
  * @param clock
  * @tparam K
  * @tparam V
  */
//Can't make it follow Scala's conventions regarding collections (CanBuildFrom...), given that
//CanBuildFrom is not compatible with the concept of the clock and the internal timestamps
case class FiniteSizedMap[K, V](maxSize: Int, expiration: FiniteDuration, clock: Clock) {

  case class Expiring(value: V, expirationTimestamp: Instant) {
    def hasExpired =
      expirationTimestamp.isBefore(clock.instant())
  }

  private val map: mutable.LinkedHashMap[K, Expiring] = mutable.LinkedHashMap.empty

  def get(key: K): Option[V] = map.get(key).map(_.value)

  def put(key: K, value: V): Option[V] = {
    if (maxSize > map.size) {
      map += ((key, Expiring(value, clock.instant().plusMillis(expiration.toMillis))))
      Some(value)
    } else if (map.head._2.hasExpired) {
      map -= map.head._1
      put(key, value)
    } else None
  }

  def +=(keyValue: (K, V)) = {
    put(keyValue._1, keyValue._2)
    this
  }

  def -=(key: K) = map -= key

  def dropExpired: Seq[(K, V)] = {
    val dropped = map.takeWhile(_._2.hasExpired)
    dropped.foreach(elem => map -= elem._1)
    dropped.map(pair => (pair._1, pair._2.value)).toSeq
  }

  def values = map.values.map(_.value)

  def size = map.size
}
