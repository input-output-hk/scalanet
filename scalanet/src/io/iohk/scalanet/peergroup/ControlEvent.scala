package io.iohk.scalanet.peergroup

import scala.util.control.NoStackTrace

sealed trait ControlEvent

object ControlEvent {

  case object Initialized

  case class InitializationError(message: String, cause: Throwable)
      extends RuntimeException(message, cause)
      with NoStackTrace
}
