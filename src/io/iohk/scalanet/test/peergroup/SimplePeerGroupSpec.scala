package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.decco.Codec.heapCodec
import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils._
//import monix.eval.Task
import org.scalatest.EitherValues._
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.Future
import scala.concurrent.duration._

class SimplePeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 5 seconds, interval = 100 millis)

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

  it should "send and receive a message to another peer of SimplePeerGroup" in new SimpleTerminalPeerGroups {
    terminalPeerGroups.foreach { terminalGroup =>
      withTwoSimplePeerGroups(
        terminalGroup,
        "Alice",
        "Bob"
      ) { (alice, bob) =>
        val message = "HI!! Alice"
        val codec = heapCodec[String]
        val bytes: ByteBuffer = codec.encode(message)
        val messageReceivedF = alice.messageStream.headL.runToFuture

        bob.sendMessage("Alice", bytes).runToFuture.futureValue

        val messageReceived = codec.decode(messageReceivedF.futureValue)

        messageReceived.right.value shouldBe message

        val aliceMessage = "HI!! Bob"
        val bytes1: ByteBuffer = codec.encode(aliceMessage)
        val messageReceivedByBobF = bob.messageStream.headL.runToFuture

        alice.sendMessage("Bob", bytes1).runToFuture.futureValue

        val messageReceivedByBob = codec.decode(messageReceivedByBobF.futureValue)

        messageReceivedByBob.right.value shouldBe aliceMessage
      }
    }
  }

  trait SimpleTerminalPeerGroups {
    val terminalPeerGroups = List(TcpTerminalPeerGroup, UdpTerminalPeerGroup)
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
