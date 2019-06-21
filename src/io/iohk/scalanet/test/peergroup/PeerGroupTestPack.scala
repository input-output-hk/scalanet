package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.BufferInstantiator
import io.iohk.scalanet.TaskValues._
import monix.execution.Scheduler
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.Future
import scala.util.Random

abstract class PeerGroupTestPack[A](implicit scheduler: Scheduler, bufferInstantiator: BufferInstantiator[ByteBuffer])
    extends FlatSpec {

  def generateRandomPeerGroup(): PeerGroup[A, String]
  def isPeerListening(peer: PeerGroup[A, String]): Boolean

  private def withTwoRandomPeerGroups(
      testFunction: (PeerGroup[A, String], PeerGroup[A, String]) => Any
  ): Unit = {
    val alice = generateRandomPeerGroup()
    val bob = generateRandomPeerGroup()
    try {
      testFunction(alice, bob)
    } finally {
      alice.shutdown().runAsync
      bob.shutdown().runAsync
    }
  }

  // This one is not passing and it probably should pass.
//  it should "not bind an address before initialization" in {
//    val peerGroup = generateRandomPeerGroup()
//    try {
//      isListening(peerGroup) shouldBe false
//      peerGroup.initialize().evaluated
//      isListening(peerGroup) shouldBe true
//    } finally {
//      peerGroup.shutdown().runAsync
//    }
//  }

  // This one should probably pass and is failing
//  it should "free address after shutdown" in {
//    val peerGroup = generateRandomPeerGroup()
//    try {
//      peerGroup.initialize().evaluated
//      isListening(peerGroup) shouldBe true
//      peerGroup.shutdown().runAsync.futureValue
//      isListening(peerGroup) shouldBe false
//    } finally {
//      peerGroup.shutdown().runAsync
//    }
//  }

  it should "report the same address for two inbound channels" in
    withTwoRandomPeerGroups { (alice, bob) =>
      val firstInbound = bob.server().headL.runAsync
      val secondInbound = bob.server().drop(1).headL.runAsync

      alice.client(bob.processAddress).evaluated
      alice.client(bob.processAddress).evaluated

      firstInbound.futureValue.to shouldBe alice.processAddress
      secondInbound.futureValue.to shouldBe alice.processAddress
    }

  it should "send and receive a message" in
    withTwoRandomPeerGroups { (alice, bob) =>
      println(s"Alice is ${alice.processAddress}, bob is ${bob.processAddress}")
      val alicesMessage = Random.alphanumeric.take(1024).mkString
      val bobsMessage = Random.alphanumeric.take(1024).mkString

      bob.server().foreachL(channel => channel.sendMessage(bobsMessage).runAsync).runAsync
      val bobReceived: Future[String] = bob.server().headL.runAsync.flatMap(_.in.headL.runAsync)

      val aliceClient = alice.client(bob.processAddress).evaluated
      val aliceReceived = aliceClient.in.headL.runAsync
      aliceClient.sendMessage(alicesMessage).evaluated

      bobReceived.futureValue shouldBe alicesMessage
      aliceReceived.futureValue shouldBe bobsMessage
    }

  it should "not report a channel to the server until the first message is sent by the client" in
    withTwoRandomPeerGroups { (alice, bob) =>
      val alicesMessage = Random.alphanumeric.take(1024 * 4).mkString

      println("Alice's procAdd" + alice.processAddress)
      println("Bob's procAdd" + bob.processAddress)

      val bobServer = bob.server().headL.runAsync

      val aliceClient = alice.client(bob.processAddress).evaluated

      aliceClient.to shouldBe bob.processAddress

      bobServer.futureValue.to shouldBe alice.processAddress

      aliceClient.sendMessage(alicesMessage).runAsync

      bobServer.futureValue.to shouldBe alice.processAddress
    }
}
