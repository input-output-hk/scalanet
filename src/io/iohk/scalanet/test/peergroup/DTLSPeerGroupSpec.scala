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

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class DTLSPeerGroupSpec extends FlatSpec {

  behavior of "DTLSPeerGroup"

  it should "send and receive a message" in withTwoRandomDTLSPeerGroups[String] { (alice, bob) =>
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

  it should "shutdown cleanly" in {
    val pg1 = randomDTLSPeerGroup[String]
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runToFuture.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }

  def withTwoRandomDTLSPeerGroups[M](
      testCode: (DTLSPeerGroup[M], DTLSPeerGroup[M]) => Any
  )(implicit codec: Codec[M]): Unit = {
    val pg1 = randomDTLSPeerGroup
    val pg2 = randomDTLSPeerGroup
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }

  def randomDTLSPeerGroup[M](implicit codec: Codec[M]): DTLSPeerGroup[M] = {
    val pg = new DTLSPeerGroup[M](Config(aRandomAddress(), randomCert.getPublicKey, randomKey))
    Await.result(pg.initialize().runToFuture, 10 seconds)
    pg
  }

  def randomKey: PrivateKey = {
    val aliases = NetUtils.keyStore.aliases().asScala.toIndexedSeq
    val alias = aliases(Random.nextInt(aliases.length))
    NetUtils.keyStore.getKey(alias, "password".toCharArray).asInstanceOf[PrivateKey]
  }

  def randomCert: Certificate = {
    val aliases = NetUtils.keyStore.aliases().asScala.toIndexedSeq
    val alias = aliases(Random.nextInt(aliases.length))
    NetUtils.keyStore.getCertificate(alias)
  }
}
