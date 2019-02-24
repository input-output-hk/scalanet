package io.iohk.network

import java.net.InetSocketAddress

import io.iohk.network.discovery.NetworkDiscovery
import io.iohk.codecs.nio._
import io.iohk.codecs.nio.auto._

import scala.reflect.runtime.universe.TypeTag
import io.iohk.network.monixstream.MonixMessageStream
import io.iohk.network.transport.Transports.usesTcp
import io.iohk.network.transport._
import io.iohk.codecs.nio.auto._
import scala.reflect.runtime.universe._
import org.slf4j.LoggerFactory

/**
  * Represents a conversational model of the network
  * where peers exchange messages in a point to point
  * fashion.
  *
  * The term 'conversational' is used in the sense introduced
  * by Van Jacobson, to distinguish from the 'disseminational'
  * networking style.
  *
  * @param networkDiscovery Encapsulates a routing table implementation.
  * @param transports helpers to obtain network transport instances.
  */
class ConversationalNetwork[Message: NioCodec: TypeTag](networkDiscovery: NetworkDiscovery, transports: Transports) {
  private val log = LoggerFactory.getLogger(classOf[ConversationalNetwork[Message]])
  val peerConfig: PeerConfig = transports.peerConfig

  /**
    * Send a message to another network address.
    *
    * The process for sending a message is
    * 1. Wrap the caller's message, setting src and dst headers.
    * 2. Examine the routing table to find a suitable peer to which to send the messagesee if an existing peer has the given address.
    *    If so, send the message directly to that peer.
    * 3. Otherwise, send the message to 'suitable' peers in the routing table.
    *
    * @param nodeId the address of the peer to which to send the message
    * @param message the message body itself.
    */
  def sendMessage(nodeId: NodeId, message: Message): Unit =
    sendMessage(Frame(FrameHeader(peerConfig.nodeId, nodeId, peerConfig.transportConfig.messageTtl), message))

  def messageStream: MessageStream[Message] =
    if (usesTcp(peerConfig))
      new MonixMessageStream(
        tcpNetworkTransport.get.monixMessageStream.filter(frameHandler).map((frame: Frame[Message]) => frame.content)
      )
    else
      MonixMessageStream.empty()

  private def frameHandler(frame: Frame[Message]): Boolean = {
    if (thisNodeIsTheDest(frame)) {
      true
    } else { // the frame is for another node
      if (frame.header.ttl > 0) {
        // decrement the ttl and resend it.
        sendMessage(frame.copy(header = FrameHeader(frame.header.src, frame.header.dst, frame.header.ttl - 1)))
        false
      } else {
        // else the ttl is zero, so discard the message
        false
      }
    }
  }

  private def thisNodeIsTheDest(frame: Frame[Message]): Boolean =
    frame.header.dst == peerConfig.nodeId

  private val tcpNetworkTransport: Option[NetworkTransport[InetSocketAddress, Frame[Message]]] =
    transports.tcp[Frame[Message]]

  private def sendMessage(frame: Frame[Message]): Unit = {
    log.debug(s"Sending message $frame")
    networkDiscovery
      .nearestPeerTo(frame.header.dst)
      .foreach(remotePeerInfo => {
        if (usesTcp(peerConfig) && usesTcp(remotePeerInfo))
          tcpNetworkTransport.get
            .sendMessage(remotePeerInfo.transportConfig.tcpTransportConfig.get.natAddress, frame)
        else
          ()
      })
  }
}
