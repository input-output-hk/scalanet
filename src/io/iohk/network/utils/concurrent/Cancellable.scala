package io.iohk.network.utils.concurrent

trait Cancellable {
  def cancel(): Unit
}
