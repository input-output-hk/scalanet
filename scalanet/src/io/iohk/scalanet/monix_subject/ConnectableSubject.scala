package io.iohk.scalanet.monix_subject

import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.{Observable, Observer}
import monix.reactive.observables.ConnectableObservable
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.{PublishSubject, Subject}

import scala.concurrent.Future

class ConnectableSubject[T](source: Subject[T, T], subject: Subject[T, T])(implicit s: Scheduler)
    extends ConnectableObservable[T]
    with Observer[T] {
  private val in = ConnectableObservable.cacheUntilConnect(source, subject)

  def connect(): Cancelable = in.connect()

  override def onNext(elem: T): Future[Ack] = source.onNext(elem)

  override def onError(ex: Throwable): Unit = source.onError(ex)

  override def onComplete(): Unit = source.onComplete()

  override def unsafeSubscribeFn(subscriber: Subscriber[T]): Cancelable = {
    in.unsafeSubscribeFn(subscriber)
  }
}

object ConnectableSubject {
  def apply[T]()(implicit s: Scheduler): ConnectableSubject[T] =
    new ConnectableSubject(PublishSubject(), PublishSubject())
  def apply[T](source: Observable[T])(implicit s: Scheduler): ConnectableObservable[T] =
    ConnectableObservable.cacheUntilConnect[T, T](source, PublishSubject())
}
