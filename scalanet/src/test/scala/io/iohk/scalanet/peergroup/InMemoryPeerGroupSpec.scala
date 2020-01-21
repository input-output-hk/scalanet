package io.iohk.scalanet.peergroup

import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.peergroup.InMemoryPeerGroup.{Network, PeerStatus}
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{BeforeAndAfter, FlatSpec}
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.Future
import scala.util.Random

class InMemoryPeerGroupSpec extends FlatSpec with BeforeAndAfter {

  import InMemoryPeerGroup.Network

  val n: Network[Int, String] = new Network[Int, String]()

  before {
    n.clear()
  }

  val peerUtils = InMemoryPeerGroupSpec.inMemoryPeerGroupTestUtils(n)

  def isListening(p: InMemoryPeerGroup[Int, String]): Boolean =
    peerUtils.isListening(p)
  def generateRandomPeerGroup(): InMemoryPeerGroup[Int, String] =
    peerUtils.generateRandomPeerGroup()

  behavior of "InMemoryPeerGroup"

  it should "not listen to the network before initialization" in {
    val peer = generateRandomPeerGroup()
    isListening(peer) shouldBe false
    peer.initialize().runToFuture
    isListening(peer) shouldBe true
    peer.shutdown().runToFuture
  }

  it should "not start a channel before the first message is sent" in
    peerUtils.withTwoRandomPeerGroups { (alice, bob) =>
      val aliceMessage = Random.alphanumeric.take(1024).mkString

      val bobServer = bob.server().collectChannelCreated.headL.runToFuture

      val aliceClient = alice.client(bob.processAddress).evaluated

      intercept[Exception] {
        bobServer.futureValue.to shouldBe alice.processAddress
      }

      aliceClient.sendMessage(aliceMessage).runToFuture
      bob.server().connect()
      bobServer.futureValue.to shouldBe alice.processAddress
    }

  it should "send and receive a message" in
    peerUtils.withTwoRandomPeerGroups { (alice, bob) =>
      println(s"Alice is ${alice.processAddress}, bob is ${bob.processAddress}")
      val alicesMessage = Random.alphanumeric.take(1024).mkString
      val bobsMessage = Random.alphanumeric.take(1024).mkString

      bob.server().collectChannelCreated.headL.runToFuture.map(_.sendMessage(bobsMessage).runToFuture)
      val bobReceived: Future[String] = bob.server().collectChannelCreated.mergeMap(_.in).headL.runToFuture

      val aliceClient = alice.client(bob.processAddress).evaluated
      val aliceReceived = aliceClient.in.headL.runToFuture

      aliceClient.sendMessage(alicesMessage).evaluated
      aliceClient.in.connect()
      bob.server().collectChannelCreated.foreach(_.in.connect())
      bob.server().connect()
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

      val firstInbound = bob.server().collectChannelCreated.headL.runToFuture
      val secondInbound = bob.server().collectChannelCreated.drop(1).headL.runToFuture

      val aliceClient1 = alice.client(bob.processAddress).evaluated
      val aliceClient2 = alice.client(bob.processAddress).evaluated

      aliceClient1.sendMessage(aliceMessage).runToFuture
      aliceClient2.sendMessage(aliceMessage).runToFuture
      bob.server().connect()
      firstInbound.futureValue.to shouldBe alice.processAddress
      secondInbound.futureValue.to shouldBe alice.processAddress
    }
}

object InMemoryPeerGroupSpec {
  def inMemoryPeerGroupTestUtils(n: Network[Int, String]): PeerUtils[Int, String, InMemoryPeerGroup] =
    PeerUtils.instance(
      _.status == PeerStatus.Listening,
      () => new InMemoryPeerGroup[Int, String](Random.nextInt())(n),
      _.shutdown(),
      _.initialize()
    )
}
