package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.decco.Codec.heapCodec
//import io.iohk.scalanet.peergroup.PeerGroup.Lift
//import java.nio.ByteBuffer

import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils
//import io.iohk.scalanet.NetUtils.randomBytes
//import io.iohk.scalanet.messagestream.MessageStream
//import io.iohk.scalanet.peergroup.SimplePeerGroup.Config
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

  behavior of "SimplePeerGroup"

  it should "send a message to itself" in withTwoSimplePeerGroups("_", "Bob") { (_, bob) =>
    val message = "HI!! Bob"
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode(message)
    val messageReceivedF = bob.messageStream.head()

    bob.sendMessage("Bob", bytes).futureValue
    val messageReceived = codec.decode(messageReceivedF.futureValue)

    messageReceived.right.value shouldBe message
  }

  it should "send a message to another peer of SimplePeerGroup" in withTwoSimplePeerGroups("Alice", "Bob") {
    (alice, bob) =>
      val message = "HI!! Bob"
      val codec = heapCodec[String]
      val bytes: ByteBuffer = codec.encode(message)
      val messageReceivedF = bob.messageStream.drop(1).head()

      alice.sendMessage("Bob", bytes).futureValue

      val messageReceived = codec.decode(messageReceivedF.futureValue)
    println("*******message*****" + messageReceived)
      println("******left******" + messageReceived.left.value)

      println("******right******" + messageReceived.right.value)

     // messageReceived.right.value shouldBe message
  }

  it should "send a message to another peer of SimplePeerGroup with underLying UDPPeerGroup" in withTwoSimpleUDPPeerGroups("Alice", "Bob") {
    (alice, bob) =>
      val message = "HI!! Bob"
      val codec = heapCodec[String]
      val bytes: ByteBuffer = codec.encode(message)
      val messageReceivedF = bob.messageStream.drop(1).head()

      alice.sendMessage("Bob", bytes).futureValue

      val messageReceived = codec.decode(messageReceivedF.futureValue)

      messageReceived.right.value shouldBe message
  }


  private def withTwoSimplePeerGroups(a: String, b: String)(
      testCode: (
          SimplePeerGroup[String, Future, InetSocketAddress],
          SimplePeerGroup[String, Future, InetSocketAddress]
      ) => Any
  ): Unit = {

    val pga = randomTCPPeerGroup
    val pgb = randomTCPPeerGroup

    val spgb = new SimplePeerGroup(SimplePeerGroup.Config(b, Map.empty[String, InetSocketAddress]), pgb)

    val spga = new SimplePeerGroup(SimplePeerGroup.Config(a, Map(b -> pgb.processAddress)), pga)

    spgb.initialize().futureValue
    spga.initialize().futureValue

    try {
      testCode(spga, spgb)
    } finally {
      pga.shutdown()
      pgb.shutdown()
    }
  }
  private def withTwoSimpleUDPPeerGroups(a: String, b: String)(
    testCode: (
      SimplePeerGroup[String, Future, InetSocketAddress],
        SimplePeerGroup[String, Future, InetSocketAddress]
      ) => Any
  ): Unit = {

    val pga = randomUDPPeerGroup
    val pgb = randomUDPPeerGroup

    val spgb = new SimplePeerGroup(SimplePeerGroup.Config(b, Map.empty[String, InetSocketAddress]), pgb)

    val spga = new SimplePeerGroup(SimplePeerGroup.Config(a, Map(b -> pgb.processAddress)), pga)

    spgb.initialize().futureValue
    spga.initialize().futureValue

    try {
      testCode(spga, spgb)
    } finally {
      pga.shutdown()
      pgb.shutdown()
    }
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
}
