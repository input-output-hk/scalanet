package io.iohk.scalanet.peergroup

import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.peergroup.SimplePeerGroup.ControlMessage
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future
import scala.concurrent.duration._
import io.iohk.scalanet.TaskValues._

import scala.util.Random

class SimplePeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 5 second, interval = 1000 millis)

  behavior of "SimplePeerGroup"

  it should "send and receive a message to another peer of SimplePeerGroup" in new SimpleTerminalPeerGroups {
    terminalPeerGroups.foreach { terminalGroup =>
      withTwoSimplePeerGroups(
        terminalGroup,
        List.empty[String],
        "Alice",
        "Bob"
      ) { (alice, bob) =>
        val alicesMessage = "hi bob, from alice"
        val bobsMessage = "hi alice, from bob"

        val bobReceived: Future[String] = bob.server()
          .flatMap(channel => channel.in)
          .filter(msg => msg == alicesMessage)
          .headL.runToFuture

        bob.server().foreach(channel => channel.sendMessage(bobsMessage).evaluated)

        val aliceClient = alice.client(bob.processAddress).evaluated

        val aliceReceived = aliceClient.in.filter(msg => msg == bobsMessage).headL.runToFuture
        aliceClient.sendMessage(alicesMessage).evaluated

        bobReceived.futureValue shouldBe alicesMessage
        aliceReceived.futureValue shouldBe bobsMessage
      }
    }
  }

  it should "send a message to itself" in new SimpleTerminalPeerGroups {
    terminalPeerGroups.foreach { terminalGroup =>
      withASimplePeerGroup(terminalGroup, "Alice") { alice =>
        val message = Random.alphanumeric.take(1044).mkString
        val aliceReceived = alice.server().flatMap(_.in).headL.runToFuture
        val aliceClient: Channel[String, String] = alice.client(alice.processAddress).evaluated
        aliceClient.sendMessage(message).runToFuture
        aliceReceived.futureValue shouldBe message
      }
    }
  }

  it should "send a message to another peer's multicast address" in new SimpleTerminalPeerGroups {
    terminalPeerGroups.foreach { terminalGroup =>
      withTwoSimplePeerGroups(
        terminalGroup,
        List("news", "sports"),
        "Alice",
        "Bob"
      ) { (alice, bob) =>
        val bobsMessage = "HI Alice"
        val alicesMessage = "HI Bob"

        val aliceReceived = alice
          .server()
          .flatMap { channel =>
            channel.sendMessage(alicesMessage).runToFuture
            channel.in
          }
          .headL
          .runToFuture

        val bobsClient: Channel[String, String] = bob.client(alice.processAddress).evaluated
        bobsClient.sendMessage(bobsMessage).runToFuture
        val bobReceived = bobsClient.in.headL.runToFuture
        aliceReceived.futureValue shouldBe bobsMessage

        val bobReceivedNews = bob
          .server()
          .flatMap { channel =>
            channel.in
          }
          .headL
          .runToFuture
        val messageNews = "Latest News"
        val aliceClient: Channel[String, String] = alice.client(bob.processAddress).evaluated
        aliceClient.sendMessage(messageNews).runToFuture

        bobReceivedNews.futureValue shouldBe messageNews

        val bobReceivedSports = bob
          .server()
          .flatMap { channel =>
            channel.in
          }
          .headL
          .runToFuture
        val messageSports = "Sports Updates"

        val aliceClientNews: Channel[String, String] = alice.client(bob.processAddress).evaluated

        aliceClientNews.sendMessage(messageSports).runToFuture
        bobReceivedSports.futureValue shouldBe messageSports

      }
    }
  }

//  it should "send a message to 2 peers sharing a multicast address" in new SimpleTerminalPeerGroups {
//    terminalPeerGroups.foreach { terminalGroup =>
//      withThreeSimplePeerGroups(
//        terminalGroup,
//        List("news", "sports"),
//        "Alice",
//        "Bob",
//        "Charlie"
//      ) { (alice, bob, charlie) =>
//        val message = "HI!! Alice"
//
//        val messageReceivedF = alice.messageChannel[String].headL.runToFuture
//        val messageReceivedByBobF = bob.messageChannel[String].headL.runToFuture
//
//        bob.sendMessage("Alice", message).runToFuture.futureValue
//        val messageReceived = messageReceivedF.futureValue
//        messageReceived shouldBe (bob.processAddress, message)
//
//        val aliceMessage = "HI!! Bob"
//        alice.sendMessage("Bob", aliceMessage).runToFuture.futureValue
//        val messageReceivedByBob = messageReceivedByBobF.futureValue
//        messageReceivedByBob shouldBe (alice.processAddress, aliceMessage)
//
//        val messageReceivedByBobNewsF = bob.messageChannel[String].headL.runToFuture
//        val messageReceivedByCharlieNewsF = charlie.messageChannel[String].headL.runToFuture
//
//        val messageNews = "Latest News"
//        alice.sendMessage("news", messageNews).runToFuture.futureValue
//        val messageReceivedByBobNews = messageReceivedByBobNewsF.futureValue
//        messageReceivedByBobNews shouldBe (alice.processAddress, messageNews)
//        val messageReceivedByCharlieNews = messageReceivedByCharlieNewsF.futureValue
//        messageReceivedByCharlieNews shouldBe (alice.processAddress, messageNews)
//
//        val messageReceivedByBobSportsF = bob.messageChannel[String].headL.runToFuture
//        val messageReceivedByCharlieSportsF = charlie.messageChannel[String].headL.runToFuture
//
//        val messageSports = "Sports Updates"
//        alice.sendMessage("sports", messageSports).runToFuture.futureValue
//        val messageReceivedByBobSports = messageReceivedByBobSportsF.futureValue
//        messageReceivedByBobSports shouldBe (alice.processAddress, messageSports)
//        val messageReceivedByCharlieSports = messageReceivedByCharlieSportsF.futureValue
//        messageReceivedByCharlieSports shouldBe (alice.processAddress, messageSports)
//
//      }
//    }
//  }

  trait SimpleTerminalPeerGroups {
    val terminalPeerGroups = List(TcpTerminalPeerGroup/*, UdpTerminalPeerGroup*/)
  }

  private def withASimplePeerGroup(
      underlyingTerminalGroup: SimpleTerminalPeerGroup,
      a: String
  )(testCode: SimplePeerGroup[String, InetMultiAddress, String] => Any): Unit = {
    withSimplePeerGroups(underlyingTerminalGroup, a, List.empty[String])(groups => testCode(groups(0)))
  }

  private def withTwoSimplePeerGroups(
      underlyingTerminalGroup: SimpleTerminalPeerGroup,
      multiCastAddresses: List[String],
      a: String,
      b: String
  )(
      testCode: (
          SimplePeerGroup[String, InetMultiAddress, String],
          SimplePeerGroup[String, InetMultiAddress, String]
      ) => Any
  ): Unit = {

    withSimplePeerGroups(underlyingTerminalGroup, a, multiCastAddresses, b)(groups => testCode(groups(0), groups(1)))
  }

  private def withThreeSimplePeerGroups(
      underlyingTerminalGroup: SimpleTerminalPeerGroup,
      multiCastAddresses: List[String],
      a: String,
      b: String,
      c: String
  )(
      testCode: (
          SimplePeerGroup[String, InetMultiAddress, String],
          SimplePeerGroup[String, InetMultiAddress, String],
          SimplePeerGroup[String, InetMultiAddress, String]
      ) => Any
  ): Unit = {

    withSimplePeerGroups(underlyingTerminalGroup, a, multiCastAddresses, b, c)(
      groups => testCode(groups(0), groups(1), groups(2))
    )
  }

  type UnderlyingMessage = Either[ControlMessage[String, InetMultiAddress], String]

  private def withSimplePeerGroups(
      underlyingTerminalGroup: SimpleTerminalPeerGroup,
      bootstrapAddress: String,
      multiCastAddresses: List[String],
      addresses: String*
  )(
      testCode: Seq[SimplePeerGroup[String, InetMultiAddress, String]] => Any
  ): Unit = {

    val bootStrapTerminalGroup = randomTerminalPeerGroup[UnderlyingMessage](underlyingTerminalGroup)
    val bootstrap = new SimplePeerGroup(
      SimplePeerGroup.Config(bootstrapAddress, List.empty[String], Map.empty[String, InetMultiAddress]),
      bootStrapTerminalGroup
    )
    bootstrap.initialize().runToFuture.futureValue

    val otherPeerGroups = addresses
      .map(
        address =>
          new SimplePeerGroup(
            SimplePeerGroup.Config(
              address,
              multiCastAddresses,
              Map(bootstrapAddress -> bootStrapTerminalGroup.processAddress)
            ),
            randomTerminalPeerGroup[UnderlyingMessage](underlyingTerminalGroup)
          )
      )
      .toList

    val futures: Seq[Future[Unit]] = otherPeerGroups.map(pg => pg.initialize().runToFuture)
    Future.sequence(futures).futureValue

    val peerGroups = bootstrap :: otherPeerGroups

    try {
      testCode(peerGroups)
    } finally {
      peerGroups.foreach(_.shutdown())
    }
  }
}
