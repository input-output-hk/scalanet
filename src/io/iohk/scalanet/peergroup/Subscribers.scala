package io.iohk.scalanet.peergroup

import monix.execution.Scheduler
import monix.reactive.subjects.{ReplaySubject}

private[scalanet] class Subscribers[T](id: String = "")(implicit scheduler: Scheduler) {

  val messageStream = ReplaySubject[T]()

  def notify(t: T): Unit = {
    messageStream.onNext(t)
  }
}
