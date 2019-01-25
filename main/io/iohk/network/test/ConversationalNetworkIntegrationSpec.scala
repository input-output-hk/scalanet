package io.iohk.network
import io.iohk.codecs.nio._
import io.iohk.codecs.nio.auto._
import org.mockito.Mockito.{atLeastOnce, verify}
import org.scalatest.FlatSpec
import org.scalatest.concurrent.Eventually._
import org.scalatest.mockito.MockitoSugar._

import scala.collection.mutable
import scala.reflect.runtime.universe._
import scala.concurrent.duration._

class ConversationalNetworkIntegrationSpec extends FlatSpec with NetworkFixture {

  implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 5 seconds)

  object Messages {
    case class A(i: Int, s: String)
    case class B(s: String, b: Boolean)
  }

  import Messages._

  behavior of "ConversationalNetwork"

  private val bootstrapStack = randomBaseNetwork(None)

  it should "send a message to a peer" in networks(bootstrapStack, randomBaseNetwork(Some(bootstrapStack))) {
    networks =>
      val bobsStack = networks(0)
      val bobsNodeId = bobsStack.transports.peerConfig.nodeId

      val bobsAInbox = mockHandler[A]
      val bobA = messageChannel(bobsStack, bobsAInbox)

      val bobsBInbox = mockHandler[B]
      val bobB = messageChannel(bobsStack, bobsBInbox)

      val alicesStack = networks(1)
      val alicesNodeId = alicesStack.transports.peerConfig.nodeId

      val alicesAInbox = mockHandler[A]
      val aliceA = messageChannel(alicesStack, alicesAInbox)

      val alicesBInbox = mockHandler[B]
      val aliceB = messageChannel(alicesStack, alicesBInbox)

      eventually {
        aliceA.sendMessage(bobsNodeId, A(1, "Hi Bob!"))
        verify(bobsAInbox, atLeastOnce()).apply(A(1, "Hi Bob!"))
      }
  }

  private def mockHandler[T]: T => Unit =
    mock[T => Unit]

  private class MessageLog[T]() {
    val messages = mutable.ListBuffer[(NodeId, T)]()

    val messageHandler: (NodeId, T) => Unit = (nodeId, message) => messages += nodeId -> message
  }

  // Create a typed message channel on top of a base network instance
  private def messageChannel[T: NioCodec: TypeTag](
      baseNetwork: BaseNetwork,
      messageHandler: T => Unit
  ): ConversationalNetwork[T] = {
    val conversationalNetwork = new ConversationalNetwork[T](baseNetwork.networkDiscovery, baseNetwork.transports)
    conversationalNetwork.messageStream.foreach(msg => messageHandler(msg))
    conversationalNetwork
  }

}
