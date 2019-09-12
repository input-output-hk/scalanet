package io.iohk.scalanet.reactive

import monix.execution.Scheduler
import monix.reactive.Observable

object ObservableOps {

  implicit class ObservableExtension[T](o: Observable[T]) {

    /**
      * Split an observable by a predicate, placing values for which the predicate returns true
      * to the right (and values for which the predicate returns false to the left).
      * This is consistent with the convention adopted by Either.cond.
      */
    def split(
        p: T => Boolean
    )(implicit scheduler: Scheduler): (Observable[T], Observable[T]) = {
      splitEither[T, T](elem => Either.cond(p(elem), elem, elem))
    }

    /**
      * Split an observable into a pair of Observables, one left, one right, according
      * to a determinant function.
      */
    def splitEither[U, V](
        f: T => Either[U, V]
    )(implicit scheduler: Scheduler): (Observable[U], Observable[V]) = {

      val oo = o.map(f)

      val l = oo.collect {
        case Left(u) => u
      }

      val r = oo.collect {
        case Right(v) => v
      }

      (l, r)
    }
  }
}
