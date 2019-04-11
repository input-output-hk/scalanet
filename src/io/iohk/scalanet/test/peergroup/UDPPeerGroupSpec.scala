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

  it should "send and receive a large message" in withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
    val alicesMessage = Random.alphanumeric.take(1024 * 5).mkString
    val bobsMessage = Random.alphanumeric.take(1024 * 5).mkString

    val bobReceived: Future[String] = bob.server().flatMap(channel => channel.in).headL.runToFuture
    bob.server().foreach(channel => channel.sendMessage(bobsMessage).runToFuture)

    val aliceClient = alice.client(bob.processAddress).evaluated
    val aliceReceived = aliceClient.in.headL.runToFuture
    aliceClient.sendMessage(alicesMessage).runToFuture

    bobReceived.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage
  }

  it should "shutdown cleanly" in {
    val pg1 = randomUDPPeerGroup[String]
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runToFuture.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }
}
