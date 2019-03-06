package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress

import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils._
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

class SimplePeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 1 second, interval = 100 millis)

  behavior of "SimplePeerGroup"

  it should "send a message to itself" in new SimpleTerminalPeerGroups {
    terminalPeerGroups.foreach { terminalGroup =>
      withASimplePeerGroup(terminalGroup, "Alice") { alice =>
        // FIXME when this number is increased, the test fails cos the string gets truncated.
        val message = Random.alphanumeric.take(1012).mkString
        val messageReceivedF = alice.messageChannel[String].headL.runToFuture

        alice.sendMessage("Alice", message).runToFuture.futureValue

        val value = messageReceivedF.futureValue
        value shouldBe message
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
        val messageReceivedF = alice.messageChannel[String].headL.runToFuture

        bob.sendMessage("Alice", message).runToFuture.futureValue

        val messageReceived: (String, String) = messageReceivedF.futureValue

        messageReceived shouldBe message

        val aliceMessage = "HI!! Bob"
        val messageReceivedByBobF = bob.messageChannel[String].headL.runToFuture

        alice.sendMessage("Bob", aliceMessage).runToFuture.futureValue

        val messageReceivedByBob = messageReceivedByBobF.futureValue

        messageReceivedByBob shouldBe aliceMessage
      }
    }
  }

  trait SimpleTerminalPeerGroups {
    val terminalPeerGroups = List(TcpTerminalPeerGroup, UdpTerminalPeerGroup)
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
    val futures: Seq[Future[Unit]] = otherPeerGroups.map(pg => pg.initialize().runToFuture)
    Future.sequence(futures)

    val peerGroups = bootstrap :: otherPeerGroups

    try {
      testCode(peerGroups)
    } finally {
      peerGroups.foreach(_.shutdown())
    }
  }
}
