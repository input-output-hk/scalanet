package io.iohk.scalanet.monix_subject

import monix.execution.Ack.Continue
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.observables.ConnectableObservable
import monix.reactive.observables.ConnectableObservable.cacheUntilConnect
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.{PublishSubject, Subject}

import scala.concurrent.Future

// elements pushed to this subject via onNext are forwarded
// to the source subject where they may be cached depending
// on whether the caller has connected any subscribers.
class CacheUntilConnectSubject[T](source: Subject[T, T], subject: Subject[T, T])(implicit s: Scheduler)
  extends ConnectableSubject[T] {
  private val in: ConnectableObservable[T] = cacheUntilConnect(source, subject)


  override def size: Int = subject.size

  def connect(): Cancelable = in.connect()

  override def onNext(elem: T): Future[Ack] = source.onNext(elem)

  override def onError(ex: Throwable): Unit = source.onError(ex)

  override def onComplete(): Unit = source.onComplete()

  override def unsafeSubscribeFn(subscriber: Subscriber[T]): Cancelable = {
    in.unsafeSubscribeFn(subscriber)
  }
}


class CacheUntilConnectObservable[T](source: Observable[T], subject: Subject[T, T])(implicit s: Scheduler)
  extends ConnectableSubject {

  private val in: ConnectableObservable[T] = cacheUntilConnect(source, subject)

  def connect(): Cancelable = in.connect()

  override def size: Int = subject.size

  override def onNext(elem: Nothing): Future[Ack] = Continue

  override def onError(ex: Throwable): Unit = subject.onError(ex)

  override def onComplete(): Unit = subject.onComplete()

  override def unsafeSubscribeFn(subscriber: Subscriber[Nothing]): Cancelable = Cancelable.empty
}
object CacheUntilConnectObservable {
  def apply[T](source: Observable[T])(implicit s: Scheduler): CacheUntilConnectObservable[T] = {
    new CacheUntilConnectObservable[T](source, PublishSubject())
  }
}

object CacheUntilConnectSubject {
  def apply[T]()(implicit s: Scheduler): CacheUntilConnectSubject[T] = {
    new CacheUntilConnectSubject[T](PublishSubject(), PublishSubject())
  }
}
