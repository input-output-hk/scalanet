package io.iohk.scalanet.peergroup

import java.util.concurrent.CopyOnWriteArraySet

import io.iohk.scalanet.messagestream.{MessageStream, MonixMessageStream}
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.observers.Subscriber

import scala.collection.mutable
import scala.collection.JavaConverters._

private[scalanet] class Subscribers[MessageType] {
  private val subscriberSet: mutable.Set[Subscriber.Sync[MessageType]] =
    new CopyOnWriteArraySet[Subscriber.Sync[MessageType]]().asScala

  val monixMessageStream: Observable[MessageType] =
    Observable.create(overflowStrategy = OverflowStrategy.Unbounded)((subscriber: Subscriber.Sync[MessageType]) => {

      subscriberSet.add(subscriber)

      () => subscriberSet.remove(subscriber)
    })

  val messageStream: MessageStream[MessageType] = new MonixMessageStream[MessageType](monixMessageStream)

  def notify(message: MessageType): Unit = {
    println(s"Subscriber.notify $message called. Subscribers are $subscriberSet")
    subscriberSet.foreach(_.onNext(message))
  }
}
