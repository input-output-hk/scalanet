package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock

import scala.collection.mutable

class TimeSet[T] private (clock: Clock, elems: Seq[T]) extends mutable.AbstractSet[T] {

  private val timestamps = mutable.HashMap[T, Long]()
  private val underlying = mutable.LinkedHashSet[T]()

  addAll(elems: _*)

  override def toString(): String = {
    underlying.map(elem => s"($elem, ${timestamps(elem)})").mkString(", ")
  }

  override def +=(elem: T): TimeSet.this.type = {
    addAll(elem)
    this
  }

  override def -=(elem: T): TimeSet.this.type = {
    underlying -= elem
    timestamps.remove(elem)
    this
  }

  override def contains(elem: T): Boolean = {
    underlying.contains(elem)
  }

  override def iterator: Iterator[T] = {
    underlying.iterator
  }

  def touch(elem: T): TimeSet.this.type = {
    +=(elem)
  }

  private def addAll(elems: T*): Unit = {
    val t = clock.millis()
    elems.foreach { elem =>
      timestamps.put(elem, t)
      underlying.remove(elem)
      underlying.add(elem)
    }
  }
}

object TimeSet {

  def apply[T](elems: T*): TimeSet[T] = {
    TimeSet(Clock.systemUTC(), elems: _*)
  }

  def apply[T](clock: Clock, elems: T*): TimeSet[T] = {
    new TimeSet[T](clock, elems)
  }
}
