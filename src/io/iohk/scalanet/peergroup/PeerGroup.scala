package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

sealed trait Channel[A, M] {
  def sendMessage(address: A, message: M)(implicit codec: Codec[M]): Task[Unit]
  def in: Observable[(A, M)]
}

sealed trait PeerGroup[A] {
  val processAddress: A
  def initialize(): Task[Unit]
  def sendMessage[M: Codec](address: A, message: M): Task[Unit]
  def messageChannel[M: Codec]: Observable[(A, M)]
  def channel[M: Codec]: Channel[A, M]
  def shutdown(): Task[Unit]
}

object PeerGroup {

  abstract class TerminalPeerGroup[A](implicit scheduler: Scheduler) extends PeerGroup[A] {
    val subscribers = new Subscribers[(A, ByteBuffer)]()
    val decoderTable = new DecoderTable[A]()

    subscribers.messageStream.foreach {
      case (a, byteBuffer) =>
        Codec.decodeFrame(decoderTable.entries(a), 0, byteBuffer)
    }

    override def messageChannel[MessageType](implicit codec: Codec[MessageType]): Observable[(A, MessageType)] = {
      val messageChannel = new MessageChannel(this)
      decoderTable.put(codec.typeCode.id, messageChannel.handleMessage)
      messageChannel.inboundMessages
    }

  }

  abstract class NonTerminalPeerGroup[A, AA](underlyingPeerGroup: PeerGroup[AA]) extends PeerGroup[A]

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
