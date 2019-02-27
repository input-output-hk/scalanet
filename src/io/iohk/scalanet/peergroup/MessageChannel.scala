package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.decco.PartialCodec.{DecodeResult, Failure}
import io.iohk.scalanet.messagestream.MessageStream

import scala.language.higherKinds

class MessageChannel[A, MessageType: Codec, F[_]](peerGroup: PeerGroup[A, F])(
    implicit codec: Codec[MessageType]
) {

  private val subscribers = new Subscribers[MessageType]()

  private[peergroup] def handleMessage(nextIndex: Int, byteBuffer: ByteBuffer): Unit = {
    val messageE: Either[Failure, DecodeResult[MessageType]] = codec.partialCodec.decode(nextIndex, byteBuffer)
    messageE match {
      case Left(Failure) =>
        println(s"OH DEAR, DECODING FAILED")
      case Right(decodeResult) =>
        println(s"${peerGroup.processAddress} Got a successful decode $decodeResult. Notifying subscribers")
        subscribers.notify(decodeResult.decoded)
    }
  }

  val inboundMessages: MessageStream[MessageType] = subscribers.messageStream

  def sendMessage(address: A, message: MessageType): F[Unit] = {
    peerGroup.sendMessage(address, codec.encode(message))
  }
}
