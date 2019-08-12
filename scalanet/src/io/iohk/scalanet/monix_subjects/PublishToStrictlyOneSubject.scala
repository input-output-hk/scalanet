package io.iohk.scalanet.monix_subjects

import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.execution.atomic.Atomic
import monix.execution.cancelables.BooleanCancelable
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.Subject

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}

/**
  * Copied from PublishToOneSubject with modifications to throw when multiple subscribers are added.
  * This is distinct from PublishToOneSubject, which invokes onError.
  */
final class PublishToStrictlyOneSubject[A] private () extends Subject[A, A] with BooleanCancelable {
  import PublishToStrictlyOneSubject.{canceledState, pendingCompleteState}

  private[this] val subscriptionP = Promise[Ack]()
  private[this] var errorThrown: Throwable = _
  private[this] val ref = Atomic(null: Subscriber[A])

  val subscription = subscriptionP.future

  def size: Int =
    ref.get match {
      case null | `pendingCompleteState` | `canceledState` => 0
      case _ => 1
    }

  def unsafeSubscribeFn(subscriber: Subscriber[A]): Cancelable =
    ref.get match {
      case null =>
        if (!ref.compareAndSet(null, subscriber))
          unsafeSubscribeFn(subscriber) // retry
        else {
          subscriptionP.success(Continue)
          this
        }

      case `pendingCompleteState` =>
        if (!ref.compareAndSet(pendingCompleteState, canceledState))
          unsafeSubscribeFn(subscriber)
        else if (errorThrown != null) {
          subscriber.onError(errorThrown)
          subscriptionP.success(Stop)
          Cancelable.empty
        } else {
          subscriber.onComplete()
          subscriptionP.success(Stop)
          Cancelable.empty
        }

      case s =>
        throw new IllegalArgumentException(
          s"PublishToStrictlyOneSubject does not allow multiple subscriptions. " +
            s"Unable to register new subscriber $subscriber. " +
            s"Subscriber $s is already registered."
        )
    }

  def onNext(elem: A): Future[Ack] =
    ref.get match {
      case null => Continue
      case subscriber =>
        subscriber.onNext(elem)
    }

  def onError(ex: Throwable): Unit = {
    errorThrown = ex
    signalComplete()
  }

  def onComplete(): Unit =
    signalComplete()

  @tailrec private def signalComplete(): Unit = {
    ref.get match {
      case null =>
        if (!ref.compareAndSet(null, pendingCompleteState))
          signalComplete() // retry
      case `pendingCompleteState` | `canceledState` =>
        () // do nothing
      case subscriber =>
        if (!ref.compareAndSet(subscriber, canceledState))
          signalComplete() // retry
        else if (errorThrown != null)
          subscriber.onError(errorThrown)
        else
          subscriber.onComplete()
    }
  }

  def isCanceled: Boolean =
    ref.get eq canceledState

  def cancel(): Unit =
    ref.set(canceledState)
}

object PublishToStrictlyOneSubject {
  def apply[A](): PublishToStrictlyOneSubject[A] =
    new PublishToStrictlyOneSubject[A]()

  private final val canceledState = new EmptySubscriber[Any]
  private final val pendingCompleteState = new EmptySubscriber[Any]

  private final class EmptySubscriber[-A] extends Subscriber.Sync[A] {
    implicit def scheduler: Scheduler =
      throw new IllegalStateException("EmptySubscriber.scheduler")

    def onNext(elem: A): Ack = Stop
    def onError(ex: Throwable): Unit = ()
    def onComplete(): Unit = ()
  }
}
