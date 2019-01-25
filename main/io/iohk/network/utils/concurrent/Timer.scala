package io.iohk.network.utils.concurrent

import scala.concurrent.duration.FiniteDuration
import monix.execution.Scheduler.Implicits.global
import monix.eval.Task

object Timer {
  def schedule[T](delay: FiniteDuration)(task: => T): CancellableFuture[T] =
    CancellableFuture(Task(task).delayExecution(delay).runAsync)
}
