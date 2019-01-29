package io.iohk.network.transport
import monix.reactive.Observable

object NetworkTransport {
  class TransportInitializationException extends RuntimeException
}

/**
  * NetworkTransports define a p2p abstraction over TCP, TLS, UDP, RLPx, etc.
  */
trait NetworkTransport[Address, Message] {

  /**
    * Send a message to another peer.
    *
    * @param address the address of the peer to which to send the message
    * @param message the message body itself.
    */
  def sendMessage(address: Address, message: Message): Unit

  /**
    * Get the inbound message stream.
    */
  def monixMessageStream: Observable[Message]
}
