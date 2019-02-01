package io.iohk.scalanet.messagestream

trait Cancellable {
  def cancel(): Unit
}
