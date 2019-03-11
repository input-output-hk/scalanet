package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

sealed trait PeerGroup[A] {
  val processAddress: A
  def initialize(): Task[Unit]
  def sendMessage[MessageType: Codec](address: A, message: MessageType): Task[Unit]
  def messageChannel[MessageType: Codec]: Observable[MessageType]
  def shutdown(): Task[Unit]
}

object PeerGroup {

  abstract class TerminalPeerGroup[A](implicit scheduler: Scheduler) extends PeerGroup[A] {
    val subscribers = new Subscribers[ByteBuffer]()
    val decoderTable: DecoderTable = new DecoderTable()

    subscribers.messageStream.foreach { byteBuffer =>
      Codec.decodeFrame(decoderTable.entries, 0, byteBuffer)
    }

    override def messageChannel[MessageType](implicit codec: Codec[MessageType]): Observable[MessageType] = {
      val messageChannel = new MessageChannel(this)
      decoderTable.decoderWrappers.put(codec.typeCode.id, messageChannel.handleMessage)
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
    s"Failed initialization of ${classOf[TCPPeerGroup]} with config $config. Cause follows."

}
