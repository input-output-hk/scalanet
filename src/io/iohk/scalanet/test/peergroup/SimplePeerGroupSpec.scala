package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress

import io.iohk.decco.Codec
import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils._
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.Future
import scala.concurrent.duration._

class SimplePeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 1 second, interval = 100 millis)

  behavior of "SimplePeerGroup"

  it should "send a message to itself" in new SimpleTerminalPeerGroups {
    terminalPeerGroups.foreach { terminalGroup =>
      withTypedPeer[String](terminalGroup, "Alice") { alice =>
        val message = "HI!!"
        val messageReceivedF = alice.inboundMessages.headL.runToFuture

        alice.sendMessage("Alice", message).runToFuture.futureValue
        messageReceivedF.futureValue shouldBe message
      }
    }
  }

//  it should "send and receive a message to another peer of SimplePeerGroup" in new SimpleTerminalPeerGroups {
//    terminalPeerGroups.foreach { terminalGroup =>
//      withTwoTypedPeers[String](
//        terminalGroup,
//        "Alice",
//        "Bob"
//      ) { (alice, bob) =>
//        val message = "HI!! Alice"
//        val messageReceivedF = alice.inboundMessages.headL.runToFuture
//
//        bob.sendMessage("Alice", message).runToFuture.futureValue
//
//        val messageReceived: String = messageReceivedF.futureValue
//
//        messageReceived shouldBe message
//
//        val aliceMessage = "HI!! Bob"
//        val messageReceivedByBobF = bob.inboundMessages.headL.runToFuture
//
//        alice.sendMessage("Bob", aliceMessage).runToFuture.futureValue
//
//        val messageReceivedByBob = messageReceivedByBobF.futureValue
//
//        messageReceivedByBob shouldBe aliceMessage
//      }
//    }
//  }

  trait SimpleTerminalPeerGroups {
    val terminalPeerGroups = List(TcpTerminalPeerGroup/*, UdpTerminalPeerGroup*/)
  }

  private def withTypedPeer[T: Codec](underlyingTerminalGroup: SimpleTerminalPeerGroup, a: String)(
      testCode: MessageChannel[String, T] => Any
  ): Unit = {
    withASimplePeerGroup(underlyingTerminalGroup, a) { alice =>
      testCode(alice.createMessageChannel[T]())
    }
  }

  private def withASimplePeerGroup(
      underlyingTerminalGroup: SimpleTerminalPeerGroup,
      a: String
  )(testCode: SimplePeerGroup[String, InetSocketAddress] => Any): Unit = {
    withSimplePeerGroups(underlyingTerminalGroup, a)(groups => testCode(groups(0)))
  }

  private def withTwoTypedPeers[T: Codec](underlyingTerminalGroup: SimpleTerminalPeerGroup, a: String, b: String)(
    testCode: (
      MessageChannel[String, T],
        MessageChannel[String, T]
      ) => Any
  ): Unit = {
    withTwoSimplePeerGroups(underlyingTerminalGroup, a, b) { (alice, bob) =>
      testCode(alice.createMessageChannel[T](), bob.createMessageChannel[T]())
    }
  }

  private def withTwoSimplePeerGroups(underlyingTerminalGroup: SimpleTerminalPeerGroup, a: String, b: String)(
      testCode: (
          SimplePeerGroup[String, InetSocketAddress],
          SimplePeerGroup[String, InetSocketAddress]
      ) => Any
  ): Unit = {

    withSimplePeerGroups(underlyingTerminalGroup, a, b)(groups => testCode(groups(0), groups(1)))
  }

  private def withSimplePeerGroups(
      underlyingTerminalGroup: SimpleTerminalPeerGroup,
      bootstrapAddress: String,
      addresses: String*
  )(
      testCode: Seq[SimplePeerGroup[String, InetSocketAddress]] => Any
  ): Unit = {

    val bootStrapTerminalGroup = randomTerminalPeerGroup(underlyingTerminalGroup)
    val bootstrap = new SimplePeerGroup(
      SimplePeerGroup.Config(bootstrapAddress, Map.empty[String, InetSocketAddress]),
      bootStrapTerminalGroup
    )
    bootstrap.initialize().runToFuture.futureValue

    val otherPeerGroups = addresses
      .map(
        address =>
          new SimplePeerGroup(
            SimplePeerGroup.Config(address, Map(bootstrapAddress -> bootStrapTerminalGroup.processAddress)),
            randomTerminalPeerGroup(underlyingTerminalGroup)
          )
      )
      .toList
    val x: Seq[Future[Unit]] = otherPeerGroups.map(pg => pg.initialize().runToFuture)
    Future.sequence(x)

    val peerGroups = bootstrap :: otherPeerGroups

    try {
      testCode(peerGroups)
    } finally {
      peerGroups.foreach(_.shutdown())
    }
  }
}
