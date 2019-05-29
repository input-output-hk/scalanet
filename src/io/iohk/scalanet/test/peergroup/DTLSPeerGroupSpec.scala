package io.iohk.scalanet.peergroup

import java.security.PrivateKey
import java.security.cert.Certificate

import io.iohk.decco.Codec
import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.NetUtils.{aRandomAddress, isListeningUDP}
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.peergroup.DTLSPeerGroup.Config
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.RecoverMethods._

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class DTLSPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "DTLSPeerGroup"

  it should "send and receive a message" in withTwoDTLSPeerGroups[String] { (alice, bob) =>
    val alicesMessage = Random.alphanumeric.take(1024 * 4).mkString
    val bobsMessage = Random.alphanumeric.take(1024 * 4).mkString

    val bobReceived: Future[String] = bob.server().mergeMap(channel => channel.in).headL.runToFuture
    bob.server().foreach(channel => channel.sendMessage(bobsMessage).runToFuture)

    val aliceClient = alice.client(bob.processAddress).evaluated
    val aliceReceived = aliceClient.in.headL.runToFuture
    aliceClient.sendMessage(alicesMessage).runToFuture

    bobReceived.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage
  }

  it should "do multiplexing properly" in withTwoDTLSPeerGroups[String] { (alice, bob) =>
    val alicesMessage = Random.alphanumeric.take(1024 * 4).mkString
    val bobsMessage = Random.alphanumeric.take(1024 * 4).mkString

    bob.server().foreach(channel => channel.sendMessage(bobsMessage).runToFuture)

    val aliceClient1 = alice.client(bob.processAddress).evaluated
    val aliceClient2 = alice.client(bob.processAddress).evaluated

    val aliceReceived1 = aliceClient1.in.headL.runToFuture
    val aliceReceived2 = aliceClient2.in.headL.runToFuture

    aliceClient1.sendMessage(alicesMessage).runToFuture

    aliceReceived1.futureValue shouldBe bobsMessage
    recoverToSucceededIf[IllegalStateException](aliceReceived2)
  }

  it should "shutdown cleanly" in {
    val pg1 = dtlsPeerGroup[String](0)
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runToFuture.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }

  def withTwoDTLSPeerGroups[M](
      testCode: (DTLSPeerGroup[M], DTLSPeerGroup[M]) => Any
  )(implicit codec: Codec[M]): Unit = {
    val pg1 = dtlsPeerGroup(0)
    val pg2 = dtlsPeerGroup(1)
    println(s"Alice is ${pg1.processAddress}")
    println(s"Bob is ${pg2.processAddress}")
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }

  def dtlsPeerGroup[M](keyIndex: Int)(implicit codec: Codec[M]): DTLSPeerGroup[M] = {
    val config = Config(aRandomAddress(), certAt(keyIndex).getPublicKey, keyAt(keyIndex), NetUtils.trustedCerts)
    val pg = new DTLSPeerGroup[M](config)
    Await.result(pg.initialize().runToFuture, Duration.Inf)
    pg
  }

  def keyAt(index: Int): PrivateKey = {
    val aliases = NetUtils.keyStore.aliases().asScala.toIndexedSeq
    val alias = aliases(index)
    NetUtils.keyStore.getKey(alias, "password".toCharArray).asInstanceOf[PrivateKey]
  }

  def certAt(index: Int): Certificate = {
    val aliases = NetUtils.keyStore.aliases().asScala.toIndexedSeq
    val alias = aliases(index)
    NetUtils.keyStore.getCertificate(alias)
  }
}
