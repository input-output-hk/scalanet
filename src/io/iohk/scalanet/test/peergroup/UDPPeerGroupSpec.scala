package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress

import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.scalanet.NetUtils._

import scala.concurrent.Future
import scala.concurrent.duration._
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.decco.auto._
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable

import scala.util.Random

class UDPPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "UDPPeerGroup"

  it should "send and receive a large message" in withTwoRandomUDPPeerGroups { (alice, bob) =>
    println(s"Alice is ${alice.processAddress}, Bob is ${bob.processAddress}")
    val alicesMessage = Random.alphanumeric.take(1024 * 5).mkString
    val bobsMessage = Random.alphanumeric.take(1024 * 5).mkString

    val bobServer: Observable[Channel[InetSocketAddress, String]] = bob.server()

    val aliceClient = alice.client(bob.processAddress)
    val aliceReceivedF = aliceClient.in.headL.runToFuture

    val bobReceivedF: Future[String] = bobServer.flatMap(channel => channel.in).headL.runToFuture
    bobServer.foreach{channel =>
      println(s"Bob got his channel to ${channel.to} (alice is ${alice.processAddress}). Replying")
      channel.sendMessage(bobsMessage).runToFuture
    }

    aliceClient.sendMessage(alicesMessage).runToFuture
    val bobReceived = bobReceivedF.futureValue
    val aliceReceived = aliceReceivedF.futureValue

    bobReceived shouldBe alicesMessage
    aliceReceived shouldBe bobsMessage
  }

  it should "shutdown cleanly" in {
    val pg1 = randomUDPPeerGroup
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runToFuture.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }
}
