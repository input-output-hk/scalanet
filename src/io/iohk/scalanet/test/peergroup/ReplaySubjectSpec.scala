package io.iohk.scalanet.peergroup

import monix.reactive.subjects.ReplaySubject
import org.scalatest.FlatSpec
import monix.execution.Scheduler.Implicits.global

class ReplaySubjectSpec extends FlatSpec {

  it should "keep going" in {
    val subject = ReplaySubject[String]()
    subject.onNext("1")
    subject.foreach(println)
    subject.onNext("2")
//    subject.onComplete()
    subject.onError(new Exception("failed"))
  }
}
