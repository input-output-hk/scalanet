package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.decco.Codec.heapCodec
//import java.nio.ByteBuffer

import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils
//import io.iohk.scalanet.NetUtils.randomBytes
//import io.iohk.scalanet.messagestream.MessageStream
import io.iohk.scalanet.peergroup.SimplePeerGroup.Config
import io.iohk.scalanet.peergroup.future._
import monix.execution.Scheduler.Implicits.global
//import org.mockito.Mockito.{verify, when}
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
//import org.scalatest.mockito.MockitoSugar._
import NetUtils._
import scala.concurrent.Future
import org.scalatest.EitherValues._
class SimplePeerGroupSpec extends FlatSpec {

//  it should "work" in withTwoRandomTCPPeerGroups { (a, b) =>
//    val bob = new SimplePeerGroup(SimplePeerGroup.Config("Bob", Map.empty[String, InetSocketAddress]), b)
//
//    val alice = new SimplePeerGroup(SimplePeerGroup.Config("Alice", Map("Bob" -> b.processAddress)), a)
//
//    Thread.sleep(5000)
//  }

  it should "send a message to it self" in withTwoRandomTCPPeerGroups { (a, b) =>
    val bob = new SimplePeerGroup(SimplePeerGroup.Config("Bob", Map.empty[String, InetSocketAddress]), a)

    // val alice = new SimplePeerGroup(SimplePeerGroup.Config("Alice", Map("Bob" -> a.processAddress)), b)
    val message = "HI!! Bob"
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode(message)
    val messageReceivedF = bob.messageStream.head()

    bob.sendMessage("Bob", bytes)
    val messageReceived = codec.decode(messageReceivedF.futureValue)

    messageReceived.right.value shouldBe message

  }

  it should "send a message to a other peer of SimplePeerGroup" in withTwoRandomTCPPeerGroups { (a, b) =>
    val bob = new SimplePeerGroup(SimplePeerGroup.Config("Bob", Map.empty[String, InetSocketAddress]), a)

    val alice = new SimplePeerGroup(SimplePeerGroup.Config("Alice", Map("Bob" -> a.processAddress)), b)
    val message = "HI!! Bob"
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode(message)
    val messageReceivedF = bob.messageStream.head()

    alice.sendMessage("Bob", bytes)

    val messageReceived = codec.decode(messageReceivedF.futureValue)

    messageReceived.right.value shouldBe message

  }

//  it should "send a message to a other peer in SimplePeerGroup" in {
//    val underlyingPeerGroup = mock[PeerGroup[String, Future]]
//    when(underlyingPeerGroup.processAddress).thenReturn("underlying")
//    val messageStream = mock[MessageStream[ByteBuffer]]
//    when(underlyingPeerGroup.messageStream()).thenReturn(messageStream)
//    val message = ByteBuffer.wrap(randomBytes(1024))
//
//    val nodeAddressB = "B"
//    val undelyingAddressB = "underlyingB"
//    when(underlyingPeerGroup.sendMessage(nodeAddressB, message)).thenReturn(Future(()))
//
//    val knownPeers = Map[String, String](nodeAddressB -> undelyingAddressB)
//    val simplePeerGroup = createSimplePeerGroup(underlyingPeerGroup, knownPeers)
//
//    simplePeerGroup.sendMessage(nodeAddressB, message)
//    verify(underlyingPeerGroup).sendMessage("underlyingB", message)
//
//  }
//
//  it should "shutdown a TCPPeerGroup properly" in {
//    val underlyingPeerGroup = mock[PeerGroup[String, Future]]
//    val nodeAddressB = "B"
//    val undelyingAddressB = "underlyingB"
//    when(underlyingPeerGroup.processAddress).thenReturn("underlying")
//    val knownPeers = Map[String, String](nodeAddressB -> undelyingAddressB)
//    val simplePeerGroup = createSimplePeerGroup(underlyingPeerGroup, knownPeers)
//    when(underlyingPeerGroup.shutdown()).thenReturn(Future(()))
//    simplePeerGroup.shutdown().futureValue shouldBe (())
//  }

  private def createSimplePeerGroup(
      underLinePeerGroup: PeerGroup[String, Future],
      knownPeers: Map[String, String]
  ): SimplePeerGroup[String, Future, String] = {
    new SimplePeerGroup(Config("A", knownPeers), underLinePeerGroup).init()
  }
}
