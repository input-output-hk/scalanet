package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
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
//import org.scalatest.Matchers._
//import org.scalatest.concurrent.ScalaFutures._
//import org.scalatest.mockito.MockitoSugar._

import scala.concurrent.Future

class SimplePeerGroupSpec extends FlatSpec {

  it should "work" in NetUtils.withTwoRandomTCPPeerGroups { (a, b) =>
    val bob = new SimplePeerGroup(SimplePeerGroup.Config("Bob", Map.empty[String, InetSocketAddress]), b)

    val alice = new SimplePeerGroup(SimplePeerGroup.Config("Alice", Map("Bob" -> b.processAddress)), a)

    Thread.sleep(5000)
  }

//  it should "send a message to a self SimplePeerGroup" in {
//    val underlyingPeerGroup = mock[PeerGroup[String, Future]]
//    when(underlyingPeerGroup.processAddress).thenReturn("underlying")
//    val messageStream = mock[MessageStream[ByteBuffer]]
//    when(underlyingPeerGroup.messageStream()).thenReturn(messageStream)
//    val message = ByteBuffer.wrap(randomBytes(1024))
//
//    val simplePeerGroup = createSimplePeerGroup(underlyingPeerGroup, Map.empty[String, String])
//    val nodeAddress = "A"
//    when(underlyingPeerGroup.sendMessage(nodeAddress, message)).thenReturn(Future(()))
//
//    simplePeerGroup.sendMessage(nodeAddress, message)
//    verify(underlyingPeerGroup).sendMessage("underlying", message)
//
//  }
//
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
