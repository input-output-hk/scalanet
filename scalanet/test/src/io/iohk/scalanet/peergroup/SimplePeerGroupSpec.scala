package io.iohk.scalanet.peergroup

import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.peergroup.SimplePeerGroup.ControlMessage
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures
import io.iohk.decco.auto._
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer

import scala.concurrent.Future
import scala.concurrent.duration._
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated

import scala.util.Random

class SimplePeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 5 second, interval = 1000 millis)

  behavior of "SimplePeerGroup"

  it should "send a message to itself" in withASimplePeerGroup("Alice") { alice =>
    val message = Random.alphanumeric.take(1044).mkString
    val aliceReceived = alice.server(ChannelCreated.collector).mergeMap(_.in).headL.runAsync
    val aliceClient: Channel[String, String] = alice.client(alice.processAddress).evaluated
    aliceClient.sendMessage(message).runAsync
    aliceReceived.futureValue shouldBe message
  }

  it should "send and receive a message to another peer of SimplePeerGroup" in withTwoSimplePeerGroups(
    List.empty[String],
    "Alice",
    "Bob"
  ) { (alice, bob) =>
    val alicesMessage = "hi bob, from alice"
    val bobsMessage = "hi alice, from bob"

    val bobReceived = bob.server(ChannelCreated.collector).mergeMap(_.in).headL.runAsync
    bob.server(ChannelCreated.collector).foreach(channel => channel.sendMessage(bobsMessage).evaluated)

    val aliceClient = alice.client(bob.processAddress).evaluated

    val aliceReceived = aliceClient.in.filter(msg => msg == bobsMessage).headL.runAsync
    aliceClient.sendMessage(alicesMessage).evaluated

    bobReceived.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage
  }

  it should "send a message to another peer's multicast address" in withTwoSimplePeerGroups(
    List("news", "sports"),
    "Alice",
    "Bob"
  ) { (alice, bob) =>
    val bobsMessage = "HI Alice"
    val alicesMessage = "HI Bob"

    val bobReceived = bob.server(ChannelCreated.collector).mergeMap(_.in).headL.runAsync
    bob.server(ChannelCreated.collector).foreach(channel => channel.sendMessage(bobsMessage).evaluated)

    val aliceClient = alice.client(bob.processAddress).evaluated

    val aliceReceived = aliceClient.in.filter(msg => msg == bobsMessage).headL.runAsync
    aliceClient.sendMessage(alicesMessage).evaluated

    bobReceived.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage

    val messageNews = "Latest News"

    val bobReceivedNews = bob
      .server(ChannelCreated.collector)
      .mergeMap { channel =>
        channel.in.filter(msg => msg == messageNews)
      }
      .headL
      .runAsync

    val sportUpdates = "Sports Updates"

    val bobSportsUpdate = bob
      .server(ChannelCreated.collector)
      .mergeMap { channel =>
        channel.in.filter(msg => msg == sportUpdates)
      }
      .headL
      .runAsync

    val aliceClientNews = alice.client("news").evaluated
    val aliceClientSports = alice.client("sports").evaluated

    aliceClientNews.sendMessage(messageNews).evaluated
    bobReceivedNews.futureValue shouldBe messageNews

    aliceClientSports.sendMessage(sportUpdates).evaluated
    bobSportsUpdate.futureValue shouldBe sportUpdates

  }

  it should "send a message to 2 peers sharing a multicast address" in
    withThreeSimplePeerGroups(
      List("news", "sports"),
      "Alice",
      "Bob",
      "Charlie"
    ) { (alice, bob, charlie) =>
      val bobsMessage = "HI Alice"
      val alicesMessage = "HI Bob"

      val bobReceived = bob.server(ChannelCreated.collector).mergeMap(_.in).headL.runAsync
      bob.server(ChannelCreated.collector).foreach(channel => channel.sendMessage(bobsMessage).evaluated)

      val aliceClient = alice.client(bob.processAddress).evaluated

      val aliceReceived = aliceClient.in.filter(msg => msg == bobsMessage).headL.runAsync
      aliceClient.sendMessage(alicesMessage).evaluated

      bobReceived.futureValue shouldBe alicesMessage
      aliceReceived.futureValue shouldBe bobsMessage

      val messageNews = "Latest News"

      val bobReceivedNews = bob
        .server(ChannelCreated.collector)
        .mergeMap { channel =>
          channel.in.filter(msg => msg == messageNews)
        }
        .headL
        .runAsync

      val charlieReceivedNews = charlie
        .server(ChannelCreated.collector)
        .mergeMap { channel =>
          channel.in.filter(msg => msg == messageNews)
        }
        .headL
        .runAsync

      val aliceClientNews = alice.client("news").evaluated

      aliceClientNews.sendMessage(messageNews).evaluated
      bobReceivedNews.futureValue shouldBe messageNews
      charlieReceivedNews.futureValue shouldBe messageNews

      val sportUpdates = "Sports Updates"

      val bobSportsUpdate = bob
        .server(ChannelCreated.collector)
        .mergeMap { channel =>
          channel.in.filter(msg => msg == sportUpdates)
        }
        .headL
        .runAsync

      val charlieSportsUpdate = charlie
        .server(ChannelCreated.collector)
        .mergeMap { channel =>
          channel.in.filter(msg => msg == sportUpdates)
        }
        .headL
        .runAsync

      val aliceSportsClient = alice.client("sports").evaluated

      aliceSportsClient.sendMessage(sportUpdates).evaluated
      bobSportsUpdate.futureValue shouldBe sportUpdates
      charlieSportsUpdate.futureValue shouldBe sportUpdates

    }

  private def withASimplePeerGroup(
      a: String
  )(testCode: SimplePeerGroup[String, InetMultiAddress, String] => Any): Unit = {
    withSimplePeerGroups(a, List.empty[String])(groups => testCode(groups(0)))
  }

  private def withTwoSimplePeerGroups(
      multiCastAddresses: List[String],
      a: String,
      b: String
  )(
      testCode: (
          SimplePeerGroup[String, InetMultiAddress, String],
          SimplePeerGroup[String, InetMultiAddress, String]
      ) => Any
  ): Unit = {

    withSimplePeerGroups(a, multiCastAddresses, b)(groups => testCode(groups(0), groups(1)))
  }

  private def withThreeSimplePeerGroups(
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

    withSimplePeerGroups(a, multiCastAddresses, b, c)(
      groups => testCode(groups(0), groups(1), groups(2))
    )
  }

  type UnderlyingMessage = Either[ControlMessage[String, InetMultiAddress], String]

  private def withSimplePeerGroups(
      bootstrapAddress: String,
      multiCastAddresses: List[String],
      addresses: String*
  )(
      testCode: Seq[SimplePeerGroup[String, InetMultiAddress, String]] => Any
  ): Unit = {

    val bootStrapTerminalGroup = randomUDPPeerGroup[UnderlyingMessage]

    val bootstrap = new SimplePeerGroup(
      SimplePeerGroup.Config(bootstrapAddress, List.empty[String], Map.empty[String, InetMultiAddress]),
      bootStrapTerminalGroup
    )
    bootstrap.initialize().runAsync.futureValue

    val otherPeerGroups = addresses
      .map(
        address =>
          new SimplePeerGroup(
            SimplePeerGroup.Config(
              address,
              multiCastAddresses,
              Map(bootstrapAddress -> bootStrapTerminalGroup.processAddress)
            ),
            randomUDPPeerGroup[UnderlyingMessage]
          )
      )
      .toList

    val futures: Seq[Future[Unit]] = otherPeerGroups.map(pg => pg.initialize().runAsync)
    Future.sequence(futures).futureValue

    val peerGroups = bootstrap :: otherPeerGroups

    try {
      testCode(peerGroups)
    } finally {
      peerGroups.foreach(_.shutdown())
    }
  }
}
