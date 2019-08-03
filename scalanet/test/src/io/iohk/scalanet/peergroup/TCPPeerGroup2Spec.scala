package io.iohk.scalanet.peergroup

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.Codec
import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.codec.FramingCodec
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}

import scala.concurrent.Future
import scala.concurrent.duration._

class TCPPeerGroup2Spec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5 seconds)

  implicit val codec = new FramingCodec(Codec[String])

  behavior of "TCPPeerGroup"

  it should "send and receive a message" in
    with3RandomTCPPeerGroups[String] { (alice, bob, charlie) =>
      val alicesMessage = "Alice"
      val bobsMessage = "Bob"
      val charliesMessage = "Charlie"
      var a = 0

      val bobReceived: Future[String] =
        bob.server().collectChannelCreated.mergeMap(channel => channel.in).headL.runAsync
      bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runAsync)
      // bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runAsync)

      val aliceClient = alice.client(bob.processAddress).evaluated


      val charlieClient = charlie.client(bob.processAddress).evaluated

      val aliceReceived = aliceClient.in.headL.runAsync
      aliceClient.sendMessage(alicesMessage).runAsync

      val charlieReceived = charlieClient.in.headL.runAsync

      charlieClient.sendMessage(charliesMessage).runAsync

      charlieClient.connect().runAsync
      aliceClient.connect().runAsync
      bob.server().connectChannels().foreachL(_ => Unit).runAsync
      bob.connect().runAsync

      bobReceived.futureValue shouldBe alicesMessage
      aliceReceived.futureValue shouldBe bobsMessage
    }

}
