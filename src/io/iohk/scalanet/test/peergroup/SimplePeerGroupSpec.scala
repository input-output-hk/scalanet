package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.decco.Codec.heapCodec
import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.peergroup.future._
import org.scalatest.EitherValues._
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class SimplePeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 5 seconds, interval = 100 millis)

  behavior of "SimplePeerGroup"

  it should "send a message to itself" in new SimpleTerminalPeerGroups {
    terminalPeerGroups.foreach { terminalGroup =>
      withASimplePeerGroup(terminalGroup, "Alice") { alice =>
        val message = "HI!!"
        val codec = heapCodec[String]
        val bytes: ByteBuffer = codec.encode(message)
        val messageReceivedF = alice.messageStream.head()

        alice.sendMessage("Alice", bytes).futureValue
        val messageReceived = codec.decode(messageReceivedF.futureValue)
        println(messageReceived)
        messageReceived.right.value shouldBe message
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
        val messageReceivedF = alice.messageStream.head()

        bob.sendMessage("Alice", bytes).futureValue

        val messageReceived = codec.decode(messageReceivedF.futureValue)

        messageReceived.right.value shouldBe message

        val aliceMessage = "HI!! Bob"
        val bytes1: ByteBuffer = codec.encode(aliceMessage)
        val messageReceivedByBobF = bob.messageStream.head()

        alice.sendMessage("Bob", bytes1).futureValue

        val messageReceivedByBob = codec.decode(messageReceivedByBobF.futureValue)

        messageReceivedByBob.right.value shouldBe aliceMessage

      }
    }

  }

  trait SimpleTerminalPeerGroups {
    val terminalPeerGroups = List(UdpTerminalPeerGroup, TcpTerminalPeerGroup)
  }

  private def withASimplePeerGroup(
      underlyingTerminalGroup: SimpleTerminalPeerGroup,
      a: String
  )(testCode: SimplePeerGroup[String, Future, InetSocketAddress] => Any): Unit = {
    withSimplePeerGroups(underlyingTerminalGroup, a)(groups => testCode(groups(0)))
  }

  private def withTwoSimplePeerGroups(underlyingTerminalGroup: SimpleTerminalPeerGroup, a: String, b: String)(
      testCode: (
          SimplePeerGroup[String, Future, InetSocketAddress],
          SimplePeerGroup[String, Future, InetSocketAddress]
      ) => Any
  ): Unit = {

    withSimplePeerGroups(underlyingTerminalGroup, a, b)(groups => testCode(groups(0), groups(1)))
  }

  private def withSimplePeerGroups(
      underlyingTerminalGroup: SimpleTerminalPeerGroup,
      bootstrapAddress: String,
      addresses: String*
  )(
      testCode: Seq[SimplePeerGroup[String, Future, InetSocketAddress]] => Any
  ): Unit = {

    val bootStrapTerminalGroup = randomTerminalPeerGroup(underlyingTerminalGroup)
    val bootstrap = new SimplePeerGroup(
      SimplePeerGroup.Config(bootstrapAddress, Map.empty[String, InetSocketAddress]),
      bootStrapTerminalGroup
    )
    bootstrap.initialize().futureValue

    val otherPeerGroups = addresses
      .map(
        address =>
          new SimplePeerGroup(
            SimplePeerGroup.Config(address, Map(bootstrapAddress -> bootStrapTerminalGroup.processAddress)),
            randomTerminalPeerGroup(underlyingTerminalGroup)
          )
      )
      .toList

    Future.sequence(otherPeerGroups.map(pg => pg.initialize())).futureValue

    val peerGroups = bootstrap :: otherPeerGroups

    try {
      testCode(peerGroups)
    } finally {
      peerGroups.foreach(_.shutdown())
    }
  }
}
