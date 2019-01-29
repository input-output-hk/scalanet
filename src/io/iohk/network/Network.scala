package io.iohk.network
import io.iohk.network.discovery.NetworkDiscovery
import io.iohk.codecs.nio._
import io.iohk.network.transport.Transports

import scala.reflect.runtime.universe._

/**
  * Represents a (lightweight) resource for type-safe sending, receiving and disseminating messages.
  * @param transports an application wide instance that holds the actual network resources.
  * @tparam Message the user message type.
  */
trait Network[Message] {

  def sendMessage(nodeId: NodeId, message: Message): Unit

  def disseminateMessage(message: Message): Unit

  def messageStream: MessageStream[Message]
}

object Network {
  def apply[Message: NioCodec: TypeTag](networkDiscovery: NetworkDiscovery, transports: Transports): Network[Message] =
    new NetworkImpl[Message](networkDiscovery, transports)
}

class NetworkImpl[Message: NioCodec: TypeTag](networkDiscovery: NetworkDiscovery, transports: Transports)
    extends Network[Message] {

  private val conversationalNetwork = new ConversationalNetwork[Message](networkDiscovery, transports)
  private val disseminationalNetwork = new DisseminationalNetwork[Message](networkDiscovery, transports)

  override def sendMessage(nodeId: NodeId, message: Message): Unit =
    conversationalNetwork.sendMessage(nodeId, message)

  override def disseminateMessage(message: Message): Unit =
    disseminationalNetwork.disseminateMessage(message)

  override def messageStream: MessageStream[Message] = conversationalNetwork.messageStream
}
