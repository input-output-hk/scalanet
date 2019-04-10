package io.iohk.scalanet.peergroup

import io.iohk.scalanet.NetUtils._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import io.iohk.decco.auto._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

class TCPPeerGroupSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "TCPPeerGroup"

  it should "send and receive a message" in
    withTwoRandomTCPPeerGroups { (alice, bob) =>
      println(s"Alice is ${alice.processAddress}, bob is ${bob.processAddress}")
      val alicesMessage = Random.alphanumeric.take(1024 * 5).mkString
      val bobsMessage = Random.alphanumeric.take(1024 * 5).mkString

      val bobReceived: Future[String] = bob.server().flatMap(channel => channel.in).headL.runToFuture
      bob.server().foreach(channel => channel.sendMessage(bobsMessage).runToFuture)

      val aliceClient = alice.client(bob.processAddress)
      val aliceReceived = aliceClient.in.headL.runToFuture
      aliceClient.sendMessage(alicesMessage).runToFuture

      bobReceived.futureValue shouldBe alicesMessage
      aliceReceived.futureValue shouldBe bobsMessage
   }

  it should "shutdown a TCPPeerGroup properly" in {
    val tcpPeerGroup = randomTCPPeerGroup
    isListening(tcpPeerGroup.config.bindAddress) shouldBe true

    tcpPeerGroup.shutdown().runToFuture.futureValue

    isListening(tcpPeerGroup.config.bindAddress) shouldBe false
  }
}
