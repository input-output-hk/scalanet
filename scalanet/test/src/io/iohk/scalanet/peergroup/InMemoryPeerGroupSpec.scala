package io.iohk.scalanet.peergroup

import io.iohk.scalanet.TaskValues._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{BeforeAndAfter, FlatSpec}
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.Future
import scala.util.Random

class InMemoryPeerGroupSpec extends FlatSpec with BeforeAndAfter {

  import InMemoryPeerGroup.Network

  implicit val n: Network[Int, String] = new Network[Int, String]()

  before {
    n.clear()
  }

  val peerUtils = PeerUtils[Int, String, InMemoryPeerGroup]

  def isListening(p: InMemoryPeerGroup[Int, String]): Boolean =
    peerUtils.isListening(p)
  def generateRandomPeerGroup(): InMemoryPeerGroup[Int, String] =
    peerUtils.generateRandomPeerGroup()

  behavior of "InMemoryPeerGroupSpec.scala"

  it should "not listen to the network before initialization" in {
    val peer = generateRandomPeerGroup()
    isListening(peer) shouldBe false
    peer.initialize().runAsync
    isListening(peer) shouldBe true
    peer.shutdown().runAsync
  }

  it should "not start a channel before the first message is sent" in
    peerUtils.withTwoRandomPeerGroups { (alice, bob) =>
      val aliceMessage = Random.alphanumeric.take(1024).mkString

      val bobServer = bob.server().headL.runAsync

      val aliceClient = alice.client(bob.processAddress).evaluated

      intercept[Exception] {
        bobServer.futureValue.to shouldBe alice.processAddress
      }

      aliceClient.sendMessage(aliceMessage).runAsync

      bobServer.futureValue.to shouldBe alice.processAddress
    }

  it should "not receive a channel that started before the subscription to the server() stream" in
    peerUtils.withTwoRandomPeerGroups { (alice, bob) =>
      val aliceMessage1 = Random.alphanumeric.take(1024).mkString
      val aliceMessage2 = aliceMessage1 + "1"

      val aliceClient1 = alice.client(bob.processAddress).evaluated
      aliceClient1.sendMessage(aliceMessage1).runAsync

      val bobServer = bob.server().headL.runAsync

      intercept[Exception] {
        bobServer.futureValue.to shouldBe alice.processAddress
      }

      val aliceClient2 = alice.client(bob.processAddress).evaluated
      aliceClient2.sendMessage(aliceMessage2).runAsync

      bobServer.futureValue.to shouldBe alice.processAddress
      bobServer.futureValue.in.headL.evaluated shouldBe aliceMessage2
    }

  it should "send and receive a message" in
    peerUtils.withTwoRandomPeerGroups { (alice, bob) =>
      println(s"Alice is ${alice.processAddress}, bob is ${bob.processAddress}")
      val alicesMessage = Random.alphanumeric.take(1024).mkString
      val bobsMessage = Random.alphanumeric.take(1024).mkString

      bob.server().headL.runAsync.map(_.sendMessage(bobsMessage).runAsync)
      val bobReceived: Future[String] = bob.server().mergeMap(_.in).headL.runAsync

      val aliceClient = alice.client(bob.processAddress).evaluated
      val aliceReceived = aliceClient.in.headL.runAsync

      aliceClient.sendMessage(alicesMessage).evaluated

      bobReceived.futureValue shouldBe alicesMessage
      aliceReceived.futureValue shouldBe bobsMessage
    }

  it should "shutdown an InMemoryPeerGroup properly" in {
    val peerGroup = generateRandomPeerGroup()
    peerGroup.initialize().evaluated
    isListening(peerGroup) shouldBe true
    peerGroup.shutdown().evaluated
    isListening(peerGroup) shouldBe false
  }

  it should "report the same address for two inbound channels" in
    peerUtils.withTwoRandomPeerGroups { (alice, bob) =>
      val aliceMessage = Random.alphanumeric.take(1024).mkString

      val firstInbound = bob.server().headL.runAsync
      val secondInbound = bob.server().drop(1).headL.runAsync

      val aliceClient1 = alice.client(bob.processAddress).evaluated
      val aliceClient2 = alice.client(bob.processAddress).evaluated

      aliceClient1.sendMessage(aliceMessage).runAsync
      aliceClient2.sendMessage(aliceMessage).runAsync

      firstInbound.futureValue.to shouldBe alice.processAddress
      secondInbound.futureValue.to shouldBe alice.processAddress
    }
}
