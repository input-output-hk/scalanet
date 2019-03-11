package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.decco.PartialCodec.{DecodeResult, Failure}
import monix.reactive.Observable
import org.slf4j.LoggerFactory

private[scalanet] class MessageChannel[A, MessageType](peerGroup: PeerGroup[A])(
    implicit codec: Codec[MessageType]
) {

  private val log = LoggerFactory.getLogger(getClass)

  private val subscribers = new Subscribers[MessageType]()

  val inboundMessages: Observable[MessageType] = subscribers.messageStream

  private[peergroup] def handleMessage(nextIndex: Int, byteBuffer: ByteBuffer): Unit = {
    val messageE: Either[Failure, DecodeResult[MessageType]] = codec.partialCodec.decode(nextIndex, byteBuffer)
    messageE match {
      case Left(Failure) =>
        log.debug(
          s"Decode failed in typed channel for peer address '${peerGroup.processAddress}' using codec '${codec.typeCode}'"
        )
      case Right(decodeResult) =>
        log.debug(
          s"Successful decode in typed channel for peer address '${peerGroup.processAddress}' using codec '${codec.typeCode}'. Notifying subscribers. $decodeResult"
        )
        subscribers.notify(decodeResult.decoded)
    }
  }
}
