package io.iohk.scalanet.peergroup

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
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import scodec.Codec
import io.iohk.scalanet.codec.DefaultCodecs.General._
import scala.util.Random

class SimplePeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 5 second)

  behavior of "SimplePeerGroup"

  it should "send a message to itself" in withASimplePeerGroup("Alice") { alice =>
    val message = Random.alphanumeric.take(1044).mkString
    val aliceReceived = alice.server().collectChannelCreated.mergeMap(_.in).headL.runToFuture
    val aliceClient: Channel[String, String] = alice.client(alice.processAddress).evaluated

    alice.server().collectChannelCreated.foreach(_.in.connect())
    alice.server().connect()
    aliceClient.sendMessage(message).runToFuture
    aliceReceived.futureValue shouldBe message
  }

  it should "send and receive a message to another peer of SimplePeerGroup" in withTwoSimplePeerGroups(
    List.empty[String],
    "Alice",
    "Bob"
  ) { (alice, bob) =>
    val alicesMessage = "hi bob, from alice"
    val bobsMessage = "hi alice, from bob"

    val bobReceived = bob.server().collectChannelCreated.mergeMap(_.in).headL.runToFuture
    bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).evaluated)

    val aliceClient = alice.client(bob.processAddress).evaluated

    val aliceReceived = aliceClient.in.filter(msg => msg == bobsMessage).headL.runToFuture
    aliceClient.sendMessage(alicesMessage).evaluated

    aliceClient.in.connect()
    bob.server().collectChannelCreated.foreach(_.in.connect())
    bob.server().connect()

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

    val bobReceived = bob.server().collectChannelCreated.mergeMap(_.in).headL.runToFuture
    bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).evaluated)

    val messageNews = "Latest News"

    val bobReceivedNews = bob
      .server()
      .collectChannelCreated
      .mergeMap { channel =>
        channel.in.filter(msg => msg == messageNews)
      }
      .headL
      .runToFuture

    val sportUpdates = "Sports Updates"

    val bobSportsUpdate = bob
      .server()
      .collectChannelCreated
      .mergeMap { channel =>
        channel.in.filter(msg => msg == sportUpdates)
      }
      .headL
      .runToFuture

    val aliceClient = alice.client(bob.processAddress).evaluated
    val aliceClientNews = alice.client("news").evaluated
    val aliceClientSports = alice.client("sports").evaluated

    val aliceReceived = aliceClient.in.filter(msg => msg == bobsMessage).headL.runToFuture

    aliceClient.in.connect()
    bob.server().collectChannelCreated.foreach(_.in.connect())
    bob.server().connect()

    aliceClient.sendMessage(alicesMessage).evaluated
    bobReceived.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage

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

      val bobReceived = bob.server().collectChannelCreated.mergeMap(_.in).headL.runToFuture
      bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).evaluated)
      val aliceClient = alice.client(bob.processAddress).evaluated
      val aliceReceived = aliceClient.in.filter(msg => msg == bobsMessage).headL.runToFuture

      val messageNews = "Latest News"
      val aliceClientNews = alice.client("news").evaluated

      val bobReceivedNews = bob
        .server()
        .collectChannelCreated
        .mergeMap { channel =>
          channel.in.filter(msg => msg == messageNews)
        }
        .headL
        .runToFuture

      val charlieReceivedNews = charlie
        .server()
        .collectChannelCreated
        .mergeMap { channel =>
          channel.in.filter(msg => msg == messageNews)
        }
        .headL
        .runToFuture

      val aliceSportsClient = alice.client("sports").evaluated

      val sportUpdates = "Sports Updates"

      val bobSportsUpdate = bob
        .server()
        .collectChannelCreated
        .mergeMap { channel =>
          channel.in.filter(msg => msg == sportUpdates)
        }
        .headL
        .runToFuture

      val charlieSportsUpdate = charlie
        .server()
        .collectChannelCreated
        .mergeMap { channel =>
          channel.in.filter(msg => msg == sportUpdates)
        }
        .headL
        .runToFuture

      aliceClient.in.connect()
      bob.server().collectChannelCreated.foreach(_.in.connect())
      bob.server().connect()
      charlie.server().collectChannelCreated.foreach(_.in.connect())
      charlie.server().connect()

      aliceClient.sendMessage(alicesMessage).evaluated
      aliceReceived.futureValue shouldBe bobsMessage
      bobReceived.futureValue shouldBe alicesMessage

      aliceClientNews.sendMessage(messageNews).evaluated
      bobReceivedNews.futureValue shouldBe messageNews
      charlieReceivedNews.futureValue shouldBe messageNews

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

  import scodec.codecs.implicits._
  import SimplePeerGroup._

  type UnderlyingMessage = Either[ControlMessage[String, InetMultiAddress], String]

  implicit val codec: Codec[UnderlyingMessage] =
    scodec.codecs.either(Codec[Boolean], Codec[ControlMessage[String, InetMultiAddress]], Codec[String])

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
            randomUDPPeerGroup[UnderlyingMessage]
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
