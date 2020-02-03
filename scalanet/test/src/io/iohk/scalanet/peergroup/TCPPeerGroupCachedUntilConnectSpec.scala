package io.iohk.scalanet.peergroup

import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.codec.FramingCodec
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import scodec.Codec
import scodec.codecs.implicits.implicitStringCodec

import scala.concurrent.duration._

class TCPPeerGroupCachedUntilConnectSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(10 seconds)

  implicit val codec = new FramingCodec(Codec[String])

  behavior of "TCPPeerGroup"

  it should "send and receive a message" in
    with3RandomTCPPeerGroups[String] { (alice, bob, charlie) =>
      val alicesMessage = "Alice"
      val bobsMessage = "Bob"
      val charliesMessage = "Charlie"

      val bobReceived = bob.server().collectChannelCreated.mergeMap(channel => channel.in).take(2).toListL.runToFuture

      bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runToFuture)

      val aliceClient = alice.client(bob.processAddress).evaluated
      val aliceReceived = aliceClient.in.headL.runToFuture
      aliceClient.sendMessage(alicesMessage).runToFuture

      aliceClient.in.connect()
      val bobReceived1 = bob.server().collectChannelCreated.mergeMap(channel => channel.in).take(2).toListL.runToFuture

      bob.server().collectChannelCreated.foreachL(_.in.connect()).runToFuture
      bob.server().connect()

      val charlieClient = charlie.client(bob.processAddress).evaluated
      charlieClient.sendMessage(charliesMessage).runToFuture

      val charlieReceived = charlieClient.in.headL.runToFuture

      charlieClient.in.connect()

      bobReceived.futureValue shouldBe Seq(alicesMessage, charliesMessage)
      bobReceived1.futureValue shouldBe Seq(alicesMessage, charliesMessage)

      charlieReceived.futureValue shouldBe bobsMessage
      aliceReceived.futureValue shouldBe bobsMessage
    }

}
