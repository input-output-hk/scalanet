package io.iohk.scalanet.peergroup

import cats.effect.concurrent.Deferred
import monix.eval.Task
import monix.tail.Iterant
import monix.reactive.Observable
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent
import io.iohk.scalanet.peergroup.Channel.ChannelEvent

package object implicits {
  // Functions to be applied on the `.nextChannelEvent()` or `.nextServerEvent()` results.
  implicit class NextOps[A](val next: Task[Option[A]]) extends AnyVal {
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

  implicit class PeerGroupOps[A, M](val group: PeerGroup[A, M]) extends AnyVal {
    def serverEventObservable: Observable[ServerEvent[A, M]] =
      group.nextServerEvent().toObservable
  }

  implicit class ChannelOps[A, M](val channel: Channel[A, M]) extends AnyVal {
    // NB: Not making an equivalent version for Iterant because it doesn't support timeout
    // directly; instead, use `next().timeout(5.second).toIterant`
    def channelEventObservable: Observable[ChannelEvent[M]] =
      channel.nextChannelEvent().toObservable
  }
}
