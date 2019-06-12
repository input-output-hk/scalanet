package io.iohk.scalanet.peergroup

import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.scalanet.NetUtils._

import scala.concurrent.Future
import scala.concurrent.duration._
import io.iohk.decco.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.scalanet.TaskValues._

import scala.util.Random

class UDPPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "UDPPeerGroup"

  it should "send and receive a message" in withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
    val alicesMessage = Random.alphanumeric.take(1024 * 4).mkString
    val bobsMessage = Random.alphanumeric.take(1024 * 4).mkString

    val bobReceived: Future[String] = bob.server().mergeMap(channel => channel.in).headL.runAsync
    bob.server().foreach(channel => channel.sendMessage(bobsMessage).runAsync)

    val aliceClient = alice.client(bob.processAddress).evaluated
    val aliceReceived = aliceClient.in.headL.runAsync
    aliceClient.sendMessage(alicesMessage).runAsync

    bobReceived.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage
  }

  it should "shutdown cleanly" in {
    val pg1 = randomUDPPeerGroup[String]
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runAsync.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }

  it should "receive a connection" in withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
    val aliceIncommingConnections = alice.server()
    aliceIncommingConnections foreach { c =>
      println(s"Alice: ${c.to} connected to me")
    }
    aliceIncommingConnections.subscribe()
    // if you uncomment the message sending, then this works, is this the intended behaviour? i.e. for the channel not to
    // be created in the server side until we receive the first message? is this the behaviour with TCP (and other) peer groups?
    bob.client(alice.processAddress).evaluated //.sendMessage("Hi Alice")
    aliceIncommingConnections.headL.runAsync.futureValue.to shouldBe bob.processAddress
  }
}
