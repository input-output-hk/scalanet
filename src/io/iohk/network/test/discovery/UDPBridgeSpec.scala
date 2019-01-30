package io.iohk.network.discovery

import java.net.InetSocketAddress

import akka.{actor => untyped}
import akka.actor.typed.ActorSystem
import akka.io.Udp
import akka.testkit.typed.scaladsl.TestProbe
import akka.util.ByteString
import akka.{testkit => untypedKit}
import io.iohk.network.discovery.DiscoveryListener._
import io.iohk.codecs.nio.NioCodec
import io.iohk.network.{Capabilities, NodeInfo}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, MustMatchers}

import org.mockito.Mockito._
import org.mockito.ArgumentMatchers._

class UDPBridgeSpec extends FlatSpec with MustMatchers with MockitoSugar {
  implicit val untypedSystem = untyped.ActorSystem("UDPBridge_system")
  implicit val system = ActorSystem.wrap(untypedSystem)

  class UdpTestHelper {
    val codec = mock[NioCodec[DiscoveryWireMessage]]
    val listenerInbox = TestProbe[DiscoveryListenerRequest]()

    val bridge = untypedKit.TestActorRef(new UDPBridge(listenerInbox.ref, codec, _ => ()))

    val nodeAddr = new InetSocketAddress(1000)

    val socket = untypedKit.TestProbe("probe2")
    val node = NodeInfo(ByteString("1"), nodeAddr, nodeAddr, Capabilities(1))
    val addr = new InetSocketAddress(1000)
    bridge.tell(Udp.Bound(addr), socket.ref)
    listenerInbox.expectMessage(Forward(Ready(addr)))

  }

  behavior of "UDPBridge"

  it should "forward a received message" in new UdpTestHelper {
    val addr2 = new InetSocketAddress(1001)
    val data = Ping(1, node, 0L, ByteString("nonce"))
    val encodedData = ByteString("Ping")

    when(codec.decode(any())).thenReturn(Some(data))
    bridge ! Udp.Received(encodedData, addr2)

    listenerInbox.expectMessage(Forward(MessageReceived(data, addr2)))
  }

  it should "forward a sent message" in new UdpTestHelper {
    val addr2 = new InetSocketAddress(1001)
    val data = Ping(1, node, 0L, ByteString("nonce"))
    val encodedData = ByteString("Ping")
    when(codec.encode(any())).thenReturn(encodedData.toByteBuffer)
    bridge ! SendMessage(data, addr2)

    socket.expectMsg(Udp.Send(encodedData, addr2))
  }
}
