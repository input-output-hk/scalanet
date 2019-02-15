package io.iohk.scalanet.peergroup

import io.iohk.decco.PartialCodec
import io.iohk.scalanet.messagestream.MessageStream
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