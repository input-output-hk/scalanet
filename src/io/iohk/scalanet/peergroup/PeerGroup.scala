package io.iohk.scalanet.peergroup

import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

trait Channel[A, M] {
  def to: A
  def sendMessage(message: M): Task[Unit]
  def in: Observable[M]
  def close(): Task[Unit]
}

trait PeerGroup[A, M] {
  def processAddress: A
  def initialize(): Task[Unit]
  def client(to: A): Task[Channel[A, M]]
  def server(): Observable[Channel[A, M]]
  def shutdown(): Task[Unit]
}

object PeerGroup {

  abstract class TerminalPeerGroup[A, M](implicit scheduler: Scheduler, codec: Codec[M]) extends PeerGroup[A, M]

  def create[PG](pg: => PG, config: Any): Either[InitializationError, PG] =
    try {
      Right(pg)
    } catch {
      case t: Throwable =>
        Left(InitializationError(initializationErrorMsg(config), t))
    }

  def createOrThrow[PG](pg: => PG, config: Any): PG =
    try {
      pg
    } catch {
      case t: Throwable =>
        throw new IllegalStateException(initializationErrorMsg(config), t)
    }

  private def initializationErrorMsg(config: Any) =
    s"Failed initialization of peer group member with config $config. Cause follows."
}
