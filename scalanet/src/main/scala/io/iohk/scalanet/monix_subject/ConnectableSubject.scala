package io.iohk.scalanet.monix_subject


import java.util.concurrent.Semaphore

import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.{Observable, Observer}
import monix.reactive.observables.ConnectableObservable
import monix.reactive.observers.{CacheUntilConnectSubscriber, Subscriber}
import monix.reactive.subjects.{PublishSubject, Subject}

import scala.concurrent.Future

class ConnectableSubject[T](source: Subject[T, T], subject: Subject[T, T])(implicit s: Scheduler)
    extends ConnectableObservable[T]
    with Observer[T] {
  private val lock = new Semaphore(1)
  private val in = ConnectableSubject.cacheUntilConnect(source, subject)


  def connect(): Cancelable = in.connect()

  override def onNext(elem: T): Future[Ack] = {
    lock.acquire()
    val res = source.onNext(elem)
    lock.release()
    res
  }

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
    cacheUntilConnect[T, T](source, PublishSubject())

  // [JMT] Copied and pasted from monix to fix NPE.
  def cacheUntilConnect[A, B](source: Observable[A], subject: Subject[A, B])(
      implicit s: Scheduler
  ): ConnectableObservable[B] = {

    new ConnectableObservable[B] {
      private[this] val (connectable, cancelRef) = {
        val ref = CacheUntilConnectSubscriber(Subscriber(subject, s))
        val c = source.unsafeSubscribeFn(ref) // connects immediately
        (ref, c)
      }

      private[this] lazy val connection = {
        val connecting = connectable.connect()
        Cancelable { () =>
          try cancelRef.cancel()
          finally if (connecting != null) connecting.cancel() // [JMT] added this null check
        }
      }

      def connect(): Cancelable =
        connection

      def unsafeSubscribeFn(subscriber: Subscriber[B]): Cancelable =
        subject.unsafeSubscribeFn(subscriber)
    }
  }
}
