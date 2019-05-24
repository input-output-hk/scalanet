package io.iohk.scalanet.peergroup

import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.peergroup.SimplePeerGroup.ControlMessage
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures
import io.iohk.decco.auto._
import scala.concurrent.Future
import scala.concurrent.duration._
import io.iohk.scalanet.TaskValues._
import scala.util.Random

class SimplePeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 5 second, interval = 1000 millis)

  behavior of "SimplePeerGroup"

  it should "send a message to itself" in new SimpleTerminalPeerGroups {
    terminalPeerGroups.foreach { terminalGroup =>
      withASimplePeerGroup(terminalGroup, "Alice") { alice =>
        val message = Random.alphanumeric.take(1044).mkString
        val aliceReceived = alice.server().mergeMap(_.in).headL.runToFuture
        val aliceClient: Channel[String, String] = alice.client(alice.processAddress).evaluated
        aliceClient.sendMessage(message).runToFuture
        aliceReceived.futureValue shouldBe message
      }
    }
  }

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

        val bobReceived = bob.server().mergeMap(_.in).headL.runToFuture
        bob.server().foreach(channel => channel.sendMessage(bobsMessage).evaluated)

        val aliceClient = alice.client(bob.processAddress).evaluated

        val aliceReceived = aliceClient.in.filter(msg => msg == bobsMessage).headL.runToFuture
        aliceClient.sendMessage(alicesMessage).evaluated

        bobReceived.futureValue shouldBe alicesMessage
        aliceReceived.futureValue shouldBe bobsMessage
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

        val bobReceived = bob.server().mergeMap(_.in).headL.runToFuture
        bob.server().foreach(channel => channel.sendMessage(bobsMessage).evaluated)

        val aliceClient = alice.client(bob.processAddress).evaluated

        val aliceReceived = aliceClient.in.filter(msg => msg == bobsMessage).headL.runToFuture
        aliceClient.sendMessage(alicesMessage).evaluated

        bobReceived.futureValue shouldBe alicesMessage
        aliceReceived.futureValue shouldBe bobsMessage

        val messageNews = "Latest News"

        val bobReceivedNews = bob
          .server()
          .mergeMap { channel =>
            channel.in.filter(msg => msg == messageNews)
          }
          .headL
          .runToFuture

        val sportUpdates = "Sports Updates"

        val bobSportsUpdate = bob
          .server()
          .mergeMap { channel =>
            channel.in.filter(msg => msg == sportUpdates)
          }
          .headL
          .runToFuture

        val aliceClientNews = alice.client("news").evaluated
        val aliceClientSports = alice.client("sports").evaluated

        aliceClientNews.sendMessage(messageNews).evaluated
        bobReceivedNews.futureValue shouldBe messageNews

        aliceClientSports.sendMessage(sportUpdates).evaluated
        bobSportsUpdate.futureValue shouldBe sportUpdates

      }
    }
  }

  it should "send a message to 2 peers sharing a multicast address" in new SimpleTerminalPeerGroups {
    terminalPeerGroups.foreach { terminalGroup =>
      withThreeSimplePeerGroups(
        terminalGroup,
        List("news", "sports"),
        "Alice",
        "Bob",
        "Charlie"
      ) { (alice, bob, charlie) =>
        val bobsMessage = "HI Alice"
        val alicesMessage = "HI Bob"

        val bobReceived = bob.server().mergeMap(_.in).headL.runToFuture
        bob.server().foreach(channel => channel.sendMessage(bobsMessage).evaluated)

        val aliceClient = alice.client(bob.processAddress).evaluated

        val aliceReceived = aliceClient.in.filter(msg => msg == bobsMessage).headL.runToFuture
        aliceClient.sendMessage(alicesMessage).evaluated

        bobReceived.futureValue shouldBe alicesMessage
        aliceReceived.futureValue shouldBe bobsMessage

        val messageNews = "Latest News"

        val bobReceivedNews = bob
          .server()
          .mergeMap { channel =>
            channel.in.filter(msg => msg == messageNews)
          }
          .headL
          .runToFuture

        val charlieReceivedNews = charlie
          .server()
          .mergeMap { channel =>
            channel.in.filter(msg => msg == messageNews)
          }
          .headL
          .runToFuture

        val aliceClientNews = alice.client("news").evaluated

        aliceClientNews.sendMessage(messageNews).evaluated
        bobReceivedNews.futureValue shouldBe messageNews
        charlieReceivedNews.futureValue shouldBe messageNews

        val sportUpdates = "Sports Updates"

        val bobSportsUpdate = bob
          .server()
          .mergeMap { channel =>
            channel.in.filter(msg => msg == sportUpdates)
          }
          .headL
          .runToFuture

        val charlieSportsUpdate = charlie
          .server()
          .mergeMap { channel =>
            channel.in.filter(msg => msg == sportUpdates)
          }
          .headL
          .runToFuture

        val aliceSportsClient = alice.client("sports").evaluated

        aliceSportsClient.sendMessage(sportUpdates).evaluated
        bobSportsUpdate.futureValue shouldBe sportUpdates
        charlieSportsUpdate.futureValue shouldBe sportUpdates

      }
    }
  }

  trait SimpleTerminalPeerGroups {
    val terminalPeerGroups = List(TcpTerminalPeerGroup, UdpTerminalPeerGroup)
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
