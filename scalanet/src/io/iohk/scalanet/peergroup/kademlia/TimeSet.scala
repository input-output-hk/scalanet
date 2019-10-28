package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock
import java.time.Clock.systemUTC

import scala.collection.AbstractSet
import scala.collection.immutable.{HashMap, ListSet}

class TimeSet[T] private (val clock: Clock, val timestamps: HashMap[T, Long], val underlying: ListSet[T])
    extends AbstractSet[T] {

  private def this(clock: Clock) = this(clock, HashMap[T, Long](), ListSet[T]())

  private def this() = this(systemUTC())

  override def toString(): String =
    underlying.map(elem => s"($elem, ${timestamps(elem)})").mkString(", ")

  override def contains(elem: T): Boolean =
    underlying.contains(elem)

  override def +(elem: T): TimeSet[T] =
    addAll(elem)

  override def -(elem: T): TimeSet[T] =
    remove(elem)

  override def iterator: Iterator[T] =
    underlying.iterator

  def touch(elem: T): TimeSet[T] =
    this + elem

  private def remove(elem: T): TimeSet[T] = {
    new TimeSet[T](clock, timestamps - elem, underlying - elem)
  }

  private def addAll(elems: T*): TimeSet[T] = {
    val t = clock.millis()
    elems.foldLeft(this) { (acc, next) =>
      new TimeSet[T](clock, acc.timestamps + (next -> t), (acc.underlying - next) + next)
    }
  }
}

object TimeSet {

  private val emptyInstance = new TimeSet[Any]()

  def empty[T]: TimeSet[T] = emptyInstance.asInstanceOf[TimeSet[T]]

  def apply[T](elems: T*): TimeSet[T] = {
    new TimeSet[T]().addAll(elems: _*)
  }

  def apply[T](clock: Clock, elems: T*): TimeSet[T] = {
    new TimeSet[T](clock).addAll(elems: _*)
  }
}
