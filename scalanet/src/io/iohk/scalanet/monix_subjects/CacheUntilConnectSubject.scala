package io.iohk.scalanet.monix_subjects

import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.observables.ConnectableObservable.cacheUntilConnect
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.{PublishSubject, Subject}

import scala.concurrent.Future

// elements pushed to this subject via onNext are forwarded
// to the source subject where they may be cached depending
// on whether the caller has connected any subscribers.
class CacheUntilConnectSubject[T](source: Subject[T, T], subject: Subject[T, T])(implicit s: Scheduler)
    extends ConnectableSubject[T] {
  private val in = cacheUntilConnect(source, subject)

  override def size: Int = subject.size

  def connect(): Cancelable = in.connect()

  override def onNext(elem: T): Future[Ack] = source.onNext(elem)

  override def onError(ex: Throwable): Unit = source.onError(ex)

  override def onComplete(): Unit = source.onComplete()

  override def unsafeSubscribeFn(subscriber: Subscriber[T]): Cancelable = {
    in.unsafeSubscribeFn(subscriber)
  }
}

object CacheUntilConnectSubject {
  def apply[T]()(implicit s: Scheduler): CacheUntilConnectSubject[T] =
    new CacheUntilConnectSubject[T](PublishSubject(), PublishSubject())
}
