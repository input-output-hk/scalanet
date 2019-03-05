package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.decco.PartialCodec.{DecodeResult, Failure}
import monix.eval.Task
import monix.reactive.Observable
import org.slf4j.LoggerFactory

class MessageChannel[A, MessageType](val peerGroup: PeerGroup[A], val inboundMessages: Observable[MessageType])(
    implicit codec: Codec[MessageType]
) {

  def this(peerGroup: PeerGroup[A])(implicit codec: Codec[MessageType]) = {
    this(peerGroup, new Subscribers[MessageType]().messageStream)
  }

  private val log = LoggerFactory.getLogger(getClass)

  private val subscribers = new Subscribers[MessageType]()

  private[peergroup] def handleMessage(nextIndex: Int, byteBuffer: ByteBuffer): Unit = {
    val messageE: Either[Failure, DecodeResult[MessageType]] = codec.partialCodec.decode(nextIndex, byteBuffer)
    messageE match {
      case Left(Failure) =>
        log.debug(
          s"Decode failed in typed channel for peer address '${peerGroup.processAddress}' using codec '${codec.typeCode}'"
        )
      case Right(decodeResult) =>
        log.debug(
          s"Successful decode in typed channel for peer address '${peerGroup.processAddress}' using codec '${codec.typeCode}'. Notifying subscribers."
        )
        subscribers.notify(decodeResult.decoded)
    }
  }

//  val inboundMessages: Observable[MessageType] = subscribers.messageStream

  def sendMessage(address: A, message: MessageType): Task[Unit] = {
    peerGroup.sendMessage(address, message)
  }

}
