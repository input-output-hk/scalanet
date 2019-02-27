package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.decco.PartialCodec.{DecodeResult, Failure}
import io.iohk.scalanet.messagestream.{MessageStream, MonixMessageStream}

import scala.language.higherKinds

class MessageChannel[A, MessageType: Codec, F[_]](peerGroup: PeerGroup[A, F], decoderTable: DecoderTable)(
    implicit codec: Codec[MessageType]
) {

  decoderTable.decoderWrappers.put(codec.typeCode.id, handleMessage)

  private val subscribers = new Subscribers[MessageType]()

  def handleMessage(nextIndex: Int, byteBuffer: ByteBuffer): Unit = {
    val messageE: Either[Failure, DecodeResult[MessageType]] = codec.partialCodec.decode(nextIndex, byteBuffer)
    messageE match {
      case Left(Failure) =>
        println(s"OH DEAR, DECODING FAILED")
      case Right(decodeResult) =>
        println(s"${peerGroup.processAddress} Got a successful decode $decodeResult. Notifying subscribers")
        subscribers.notify(decodeResult.decoded)
    }
  }

  val inboundMessages: MessageStream[MessageType] = new MonixMessageStream(subscribers.monixMessageStream)

  // (When executed) send a message to the peer at 'address'
  // The result may raise an error if reliableDelivery is specified as one of the QoS options.
  // In that situation, it is assumed that the message will be acked and so failure to
  // receive an ack will allow failure detection.
  //
  // When reliableDelivery is not enabled, the call will always succeed, whether or not
  // the message actually reaches the remote peer.
  def sendMessage(address: A, message: MessageType): F[Unit] =
    peerGroup.sendMessage(address, codec.encode(message))
}
