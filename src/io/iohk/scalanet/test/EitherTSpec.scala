package io.iohk.scalanet.test

import io.iohk.scalanet.peergroup.eithert._

import monix.eval.Task
import monix.execution.Scheduler

import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture

import scala.concurrent.{ExecutionContext, TimeoutException}

class EitherTSpec extends FlatSpec {

  behavior of "eithert"

  it should "map a successful future correctly" in {
    val taskThatWillSucceed: Task[Unit] = Task.eval(())

    val et: ET[Unit] = liftEitherT(Scheduler(ExecutionContext.global)).run(taskThatWillSucceed)

    et.value.futureValue shouldBe Right(())
  }

  it should "map a failed future correctly" in {
    val taskThatWillFail: Task[Unit] = Task.raiseError(new TimeoutException("too slow"))

    val et: ET[Unit] = liftEitherT(Scheduler(ExecutionContext.global)).run(taskThatWillFail)

    et.value.futureValue shouldBe Left(SendError("too slow"))
  }
}
