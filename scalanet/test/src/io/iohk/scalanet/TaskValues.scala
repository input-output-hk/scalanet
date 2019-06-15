package io.iohk.scalanet

import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures._

object TaskValues {

  implicit class TaskOps[T](task: Task[T]) {
    def evaluated(implicit scheduler: Scheduler, patienceConfig: PatienceConfig): T = {
      task.runAsync.futureValue
    }
  }
}
