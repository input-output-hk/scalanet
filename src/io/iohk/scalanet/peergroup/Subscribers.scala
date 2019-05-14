package io.iohk.scalanet.peergroup

import monix.execution.Scheduler

private[scalanet] class Subscribers[T](id: String = "")(implicit scheduler: Scheduler) {

  import monix.reactive.subjects.ReplaySubject
  val messageStream = ReplaySubject[T]()

//  import monix.reactive.subjects.PublishSubject
//  val messageStream = PublishSubject[T]()
  def notify(t: T): Unit = {
    messageStream.onNext(t)
  }

  def onComplete(): Unit =
    messageStream.onComplete()
}
