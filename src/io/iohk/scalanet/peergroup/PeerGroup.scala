package io.iohk.scalanet.peergroup

//import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import monix.eval.Task
//import monix.reactive.Observable

sealed trait PeerGroup[A] {
  def initialize(): Task[Unit] = ???
//  def sendMessage(address: A, message: ByteBuffer): Task[Unit]
  def sendMessage[T: Codec](address: A, message: T): Task[Unit]
  def shutdown(): Task[Unit]
//  def messageStream(): Observable[ByteBuffer]
  val processAddress: A
  val decoderTable: DecoderTable = new DecoderTable()
  def createMessageChannel[MessageType]()(implicit codec: Codec[MessageType]): MessageChannel[A, MessageType] = {
    val messageChannel = new MessageChannel(this)
    decoderTable.decoderWrappers.put(codec.typeCode.id, messageChannel.handleMessage)
    messageChannel
  }
}

object PeerGroup {

  trait TerminalPeerGroup[A] extends PeerGroup[A]

  abstract class NonTerminalPeerGroup[A, AA](underlyingPeerGroup: PeerGroup[AA]) extends PeerGroup[A] {
  }

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
