package io.iohk.scalanet.reactive

import io.iohk.scalanet.reactive.ObservableOps._
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._

class ObservableOpsSpec extends FlatSpec {

  val isEven: Int => Boolean = _ % 2 == 0

  "ObservableExtension" should "split a cold observable" in {
    val o = Observable(1, 2, 3, 4, 5)

    val (l, r) = o.split(isEven)

    l.toListL.runToFuture.futureValue shouldBe List(1, 3, 5)
    r.toListL.runToFuture.futureValue shouldBe List(2, 4)
  }

  it should "split a hot observable" in {
    val o = PublishSubject[Int]()

    val (l, r) = o.split(isEven)
    val lbuf = l.toListL.runToFuture
    val rbuf = r.toListL.runToFuture

    Observable.fromIterable(1 to 5).mapEvalF(i => o.onNext(i)).subscribe()
    o.onComplete()

    lbuf.futureValue shouldBe List(1, 3, 5)
    rbuf.futureValue shouldBe List(2, 4)
  }
}
