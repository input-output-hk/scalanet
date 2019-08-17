package io.iohk.scalanet.peergroup

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.Codec
import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.codec.FramingCodec
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}

import scala.concurrent.duration._

class TCPPeerGroupCachetUntilConnectSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(10 seconds)

  implicit val codec = new FramingCodec(Codec[String])

  behavior of "TCPPeerGroup"

  it should "send and receive a message" in
    with3RandomTCPPeerGroups[String] { (alice, bob, charlie) =>
      val alicesMessage = "Alice"
      val bobsMessage = "Bob"
      val charliesMessage = "Charlie"

      val bobReceived = bob.server().collectChannelCreated.mergeMap(channel => channel.in).take(2).toListL.runAsync

      bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runAsync)

      val aliceClient = alice.client(bob.processAddress).evaluated
      val aliceReceived = aliceClient.in.headL.runAsync
      aliceClient.sendMessage(alicesMessage).runAsync

      aliceClient.in.asInstanceOf[ConnectableSubject[String]].connect()
      val bobReceived1 = bob.server().collectChannelCreated.mergeMap(channel => channel.in).take(2).toListL.runAsync

      bob.server().collectChannelCreated.foreachL(_.in.connect()).runAsync
      bob.server().connect()

      val charlieClient = charlie.client(bob.processAddress).evaluated
      charlieClient.sendMessage(charliesMessage).runAsync

      val charlieReceived = charlieClient.in.headL.runAsync

      charlieClient.in.asInstanceOf[ConnectableSubject[String]].connect()

      bobReceived.futureValue shouldBe Seq(alicesMessage, charliesMessage)
      bobReceived1.futureValue shouldBe Seq(alicesMessage, charliesMessage)

      charlieReceived.futureValue shouldBe bobsMessage
      aliceReceived.futureValue shouldBe bobsMessage
    }

}
