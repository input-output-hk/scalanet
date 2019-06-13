package io.iohk.scalanet.peergroup

import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.scalanet.NetUtils._

import scala.concurrent.Future
import scala.concurrent.duration._
import io.iohk.decco.auto._
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer

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

  it should "receive a channel and message that was sent before subscription to the server stream" in withTwoRandomUDPPeerGroups[
    String
  ] { (alice, bob) =>
    val alicesMessage = Random.alphanumeric.take(1024 * 4).mkString

    val aliceClient = alice.client(bob.processAddress).evaluated
    aliceClient.sendMessage(alicesMessage).runAsync

    expensiveComputation()

    val bobFirstReceivedChannel = bob.server().headL.runAsync
    bobFirstReceivedChannel.futureValue.to shouldBe alice.processAddress

    val bobReceivedMessage = bobFirstReceivedChannel.futureValue.in.headL.runAsync
    bobReceivedMessage.futureValue shouldBe alicesMessage
  }

  def expensiveComputation(): Unit = {
    def fib(n: BigInt): BigInt = if (n == 0 || n == 1) 1 else fib(n - 1) + fib(n - 2)
    fib(20)
  }

}
