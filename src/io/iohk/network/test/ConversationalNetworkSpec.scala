package io.iohk.network

import java.net.InetSocketAddress

import com.softwaremill.quicklens._
import io.iohk.network.NodeId.nodeIdBytes
import io.iohk.network.discovery.NetworkDiscovery
import io.iohk.codecs.nio._
import io.iohk.codecs.nio.auto._
import io.iohk.network.transport.{FrameHeader, Transports}
import io.iohk.network.transport.tcp.NetUtils.{aRandomAddress, forwardPort, randomBytes}
import io.iohk.network.transport.tcp.TcpTransportConfig
import org.mockito.ArgumentMatchers._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import org.scalatest.concurrent.Eventually._
import org.mockito.Mockito.{after, verify, when}
import org.scalatest.mockito.MockitoSugar._

import scala.concurrent.Future
import scala.reflect.runtime.universe._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class ConversationalNetworkSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig = PatienceConfig(1 second)

  behavior of "ConversationalNetwork"

  it should "send a message to a peer that has a common transport" in
    networks(randomNetwork[String](), randomNetwork[String]()) { networks =>
      val alice = networks(0)
      val bob = networks(1)

      when(alice.networkDiscovery.nearestPeerTo(bob.nodeId)).thenReturn(Option(bob.peerConfig))

      alice.network.sendMessage(bob.nodeId, "Hi, Bob!")

      eventually {
        verify(bob.messageHandler).apply("Hi, Bob!")
      }
    }

  it should "not send a message to a peer that does not have a common transport" in
    networks(randomNetwork[String](), randomNetwork[String]()) { networks =>
      val alice = networks(0)
      val bob = networks(1)

      when(alice.networkDiscovery.nearestPeerTo(bob.nodeId)).thenReturn(None)

      alice.network.sendMessage(bob.nodeId, "Hi, Bob!")

      verify(bob.messageHandler, after(200).never()).apply(any[String])
    }

  it should "send a message to a peer using the peer's NATed address" in
    networks(randomNetwork[String](), randomNetwork[String]()) { networks =>
      val alice = networks(0)
      val bob = networks(1)
      val bobsTransportConfig = bob.peerConfig.transportConfig.tcpTransportConfig.get

      // by resetting the bind address, we guarantee that attempting to talk to it will break the test.
      val bobsNattedConfig =
        TcpTransportConfig(bindAddress = new InetSocketAddress(0), natAddress = aRandomAddress())
      val bobsNattedPeerInfo =
        bob.peerConfig.modify(_.transportConfig.tcpTransportConfig).setTo(Option(bobsNattedConfig))

      val portForward = forwardPort(bobsNattedConfig.natAddress.getPort, bobsTransportConfig.bindAddress)
      val _ = Future {
        portForward.start()
      }

      when(alice.networkDiscovery.nearestPeerTo(bob.nodeId)).thenReturn(Option(bobsNattedPeerInfo))

      alice.network.sendMessage(bob.nodeId, "Hi, Bob!")

      eventually {
        verify(bob.messageHandler).apply("Hi, Bob!")
      }
      portForward.stop()
    }

  it should "forward messages on behalf of peers" in
    networks(randomNetwork[String](), randomNetwork[String](), randomNetwork[String]()) { networks =>
      val alice = networks(0)
      val bob = networks(1)
      val charlie = networks(2)

      when(alice.networkDiscovery.nearestPeerTo(charlie.nodeId)).thenReturn(Option(bob.peerConfig))
      when(bob.networkDiscovery.nearestPeerTo(charlie.nodeId)).thenReturn(Option(charlie.peerConfig))

      alice.network.sendMessage(charlie.nodeId, "Hi, Charlie!")

      eventually {
        verify(charlie.messageHandler).apply("Hi, Charlie!")
      }
    }

  it should "not forward messages on behalf of peers after expiration of the TTL" in
    networks(randomNetwork[String](messageTtl = 0), randomNetwork[String](), randomNetwork[String]()) { networks =>
      val alice = networks(0)
      val bob = networks(1)
      val charlie = networks(2)

      when(alice.networkDiscovery.nearestPeerTo(charlie.nodeId)).thenReturn(Option(bob.peerConfig))
      when(bob.networkDiscovery.nearestPeerTo(charlie.nodeId)).thenReturn(Option(charlie.peerConfig))

      alice.network.sendMessage(charlie.nodeId, "Hi, Charlie!")

      verify(charlie.messageHandler, after(200).never()).apply(any[String])
    }

  case class OurMessage(says: String)

  it should "support the messaging of arbitrary user objects" in
    networks(randomNetwork[OurMessage](), randomNetwork[OurMessage]()) { networks =>
      val alice = networks(0)
      val bob = networks(1)

      when(alice.networkDiscovery.nearestPeerTo(bob.nodeId)).thenReturn(Option(bob.peerConfig))

      alice.network.sendMessage(bob.nodeId, OurMessage("Hi, Bob!"))

      eventually {
        verify(bob.messageHandler).apply(OurMessage("Hi, Bob!"))
      }
    }

  private case class NetworkFixture[T](
      nodeId: NodeId,
      peerConfig: PeerConfig,
      networkDiscovery: NetworkDiscovery,
      messageHandler: T => Unit,
      transports: Transports,
      network: ConversationalNetwork[T]
  )

  private def randomNetwork[T: NioCodec: TypeTag](messageTtl: Int = FrameHeader.defaultTtl): NetworkFixture[T] = {
    val tcpAddress: InetSocketAddress = aRandomAddress()
    val configuration = TransportConfig(Some(TcpTransportConfig(tcpAddress)), messageTtl)

    val nodeId = NodeId(randomBytes(nodeIdBytes))

    val networkDiscovery: NetworkDiscovery = mock[NetworkDiscovery]

    val messageHandler: T => Unit = mock[T => Unit]

    val peerConfig = PeerConfig(nodeId, configuration)

    val transports = new Transports(peerConfig)
    val network =
      new ConversationalNetwork[T](networkDiscovery, transports)

    network.messageStream.foreach(messageHandler)

    NetworkFixture(nodeId, peerConfig, networkDiscovery, messageHandler, transports, network)
  }

  private def networks[T](fixtures: NetworkFixture[T]*)(testCode: Seq[NetworkFixture[T]] => Any): Unit = {
    try {
      testCode(fixtures)
    } finally {
      fixtures.foreach(fixture => fixture.transports.shutdown())
    }
  }
}
