package io.iohk.scalanet.monix_subjects

import monix.execution.{Cancelable, Scheduler}
import monix.reactive.observers.Subscriber

class CacheUntilConnectStrictlyOneSubject[T](implicit s: Scheduler)
    extends CacheUntilConnectSubject[T](PublishToStrictlyOneSubject[T](), PublishToStrictlyOneSubject[T]()) {
  override def unsafeSubscribeFn(subscriber: Subscriber[T]): Cancelable = {
    super.unsafeSubscribeFn(subscriber)
    connect()
  }
}

object CacheUntilConnectStrictlyOneSubject {
  def apply[T]()(implicit s: Scheduler): CacheUntilConnectStrictlyOneSubject[T] =
    new CacheUntilConnectStrictlyOneSubject[T]()
}
