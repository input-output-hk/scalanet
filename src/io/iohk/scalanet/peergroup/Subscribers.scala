package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.observers.Subscriber

import scala.collection.mutable
import scala.collection.JavaConverters._

private[scalanet] class Subscribers {
  private val subscriberSet: mutable.Set[Subscriber.Sync[ByteBuffer]] =
    new CopyOnWriteArraySet[Subscriber.Sync[ByteBuffer]]().asScala

  val monixMessageStream: Observable[ByteBuffer] =
    Observable.create(overflowStrategy = OverflowStrategy.Unbounded)((subscriber: Subscriber.Sync[ByteBuffer]) => {

      subscriberSet.add(subscriber)

      () => subscriberSet.remove(subscriber)
    })

  def notify(byteBuffer: ByteBuffer): Unit = subscriberSet.foreach(_.onNext(byteBuffer))
}
