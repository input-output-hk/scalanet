package io.iohk.scalanet.peergroup

sealed trait ControlEvent

object ControlEvent {

  case object Initialized

  case class InitializationError(message: String, cause: Throwable) extends RuntimeException(message, cause)
}
