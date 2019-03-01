package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import cats.data.Kleisli
import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import monix.eval.Task
import monix.reactive.Observable

import scala.language.higherKinds

sealed trait PeerGroup[A, F[_]] {
  def initialize(): F[Unit] = ???
  def sendMessage(address: A, message: ByteBuffer): F[Unit]
  def shutdown(): F[Unit]
  def messageStream(): Observable[ByteBuffer]
  val processAddress: A
  val decoderTable: DecoderTable = new DecoderTable()
  def createMessageChannel[MessageType]()(implicit codec: Codec[MessageType]): MessageChannel[A, MessageType, F] = {
    val messageChannel = new MessageChannel(this)
    decoderTable.decoderWrappers.put(codec.typeCode.id, messageChannel.handleMessage)
    messageChannel
  }
}

object PeerGroup {

  type Lift[F[_]] = Kleisli[F, Task[Unit], Unit]

  trait TerminalPeerGroup[A, F[_]] extends PeerGroup[A, F]

  abstract class NonTerminalPeerGroup[A, F[_], AA](underlyingPeerGroup: PeerGroup[AA, F]) extends PeerGroup[A, F]

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

  private def initializationErrorMsg[F[_]](config: Any) =
    s"Failed initialization of ${classOf[TCPPeerGroup[F]].getName} with config $config. Cause follows."

}
