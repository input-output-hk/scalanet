package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.decco.{Codec, PartialCodec}
import io.iohk.decco.PartialCodec.{DecodeResult, Failure}
import io.iohk.scalanet.messagestream.{MessageStream, MonixMessageStream}
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup

import scala.language.higherKinds

abstract class MessageChannel[A, MessageType: PartialCodec, F[_]] {

  val inboundMessages: MessageStream[MessageType]

//  val decodeErrors: MessageStream[DecoderError] = ???

  // (When executed) send a message to the peer at 'address'
  // The result may raise an error if reliableDelivery is specified as one of the QoS options.
  // In that situation, it is assumed that the message will be acked and so failure to
  // receive an ack will allow failure detection.
  //
  // When reliableDelivery is not enabled, the call will always succeed, whether or not
  // the message actually reaches the remote peer.
  def sendMessage(address: A, message: MessageType): F[Unit]
}

class TerminalGroupMessageChannel[A, MessageType: PartialCodec, F[_]](
    terminalPeerGroup: TerminalPeerGroup[InetSocketAddress, F]
) extends MessageChannel[InetSocketAddress, MessageType, F] {
  private val ev = PartialCodec[MessageType]
  private val codec = Codec.heapCodec

  private val subscribers = new Subscribers[MessageType]()

  private val handle: (Int, ByteBuffer) => Unit = (nextIndex, byteBuffer) => {
    val messageE: Either[Failure, DecodeResult[MessageType]] = ev.decode(nextIndex, byteBuffer)
    messageE match {
      case Left(Failure) =>
        println(s"OH DEAR, DECODING FAILED")
      case Right(decodeResult) =>
        println(s"Got a successful decode $decodeResult. Notifying subscribers")
        subscribers.notify(decodeResult.decoded)
    }
  }

  private val decoderWrappers: Map[String, (Int, ByteBuffer) => Unit] =
    Map(ev.typeCode -> handle)

  terminalPeerGroup.messageStream.foreach { b =>
    println(s"GOT A MESSAGE. DECODING IT." + b.toString)
    Codec.decodeFrame(decoderWrappers, 0, b)
  }
  override val inboundMessages = new MonixMessageStream(subscribers.monixMessageStream)

  override def sendMessage(address: InetSocketAddress, message: MessageType): F[Unit] = {
    terminalPeerGroup.sendMessage(address, codec.encode(message))
  }
}
