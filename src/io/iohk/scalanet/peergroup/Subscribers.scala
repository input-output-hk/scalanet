package io.iohk.scalanet.peergroup

import java.util.concurrent.CopyOnWriteArraySet

import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.observers.Subscriber

import scala.collection.mutable
import scala.collection.JavaConverters._

private[scalanet] class Subscribers[T](id: String = "") {
  val subscriberSet: mutable.Set[Subscriber.Sync[T]] =
    new CopyOnWriteArraySet[Subscriber.Sync[T]]().asScala

  val messageStream: Observable[T] =
    Observable.create(overflowStrategy = OverflowStrategy.Unbounded)((subscriber: Subscriber.Sync[T]) => {
      subscriberSet.add(subscriber)
      () => subscriberSet.remove(subscriber)
    })

  def notify(t: T): Unit = {
    println("********notification******" + t)
    subscriberSet.foreach(_.onNext(t))
  }
}
