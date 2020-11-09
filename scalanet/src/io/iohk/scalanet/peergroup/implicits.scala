package io.iohk.scalanet.peergroup

import cats.effect.concurrent.Deferred
import monix.eval.Task
import monix.tail.Iterant
import monix.reactive.Observable

package object implicits {
  // Functions to be applied on the `.nextMessage()` or `.nextServerEvent()` results.
  implicit class NextOps[A](next: Task[Option[A]]) {
    def toIterant: Iterant[Task, A] =
      Iterant.repeatEvalF(next).takeWhile(_.isDefined).map(_.get)

    def toObservable: Observable[A] =
      Observable.repeatEvalF(next).takeWhile(_.isDefined).map(_.get)

    def withCancelToken(token: Deferred[Task, Unit]): Task[Option[A]] =
      Task.race(token.get, next).map {
        case Left(()) => None
        case Right(x) => x
      }
  }
}
