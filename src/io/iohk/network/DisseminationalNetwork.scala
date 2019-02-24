package io.iohk.network

import io.iohk.network.discovery.NetworkDiscovery
import io.iohk.network.transport.Transports
import io.iohk.codecs.nio.NioCodec
import scala.reflect.runtime.universe._

class DisseminationalNetwork[Message: NioCodec: TypeTag](networkDiscovery: NetworkDiscovery, transports: Transports) {

  private val conversationalNetwork =
    new ConversationalNetwork[Message](networkDiscovery, transports)

  /**
    * Disseminate a message to those on the network who want it.
    *
    * @param message the message to send.
    */
  def disseminateMessage(message: Message): Unit =
    networkDiscovery
      .nearestNPeersTo(transports.peerConfig.nodeId, Int.MaxValue)
      .foreach(peer => {
        conversationalNetwork.sendMessage(peer.nodeId, message)
      })

}
