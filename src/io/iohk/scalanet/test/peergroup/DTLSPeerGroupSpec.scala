package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.cert.{Certificate, CertificateFactory}

import io.iohk.decco.auto._
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.NetUtils.{aRandomAddress, isListeningUDP}
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.peergroup.DTLSPeerGroup.Config._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.RecoverMethods._

import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random
import DTLSPeerGroupSpec._
import io.iohk.scalanet.peergroup.DTLSPeerGroup.Config

class DTLSPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "DTLSPeerGroup"

  it should "send and receive a message" in {
    withTwoDTLSPeerGroups[String](rawKeyConfig, signedCertConfig) { (alice, bob) =>
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
  }

  it should "do multiplexing properly" in withTwoDTLSPeerGroups[String](rawKeyConfig) { (alice, bob) =>
    val alicesMessage = Random.alphanumeric.take(1024 * 4).mkString
    val bobsMessage = Random.alphanumeric.take(1024 * 4).mkString

    bob.server().foreach(channel => channel.sendMessage(bobsMessage).runAsync)

    val aliceClient1 = alice.client(bob.processAddress).evaluated
    val aliceClient2 = alice.client(bob.processAddress).evaluated

    val aliceReceived1 = aliceClient1.in.headL.runAsync
    val aliceReceived2 = aliceClient2.in.headL.runAsync

    aliceClient1.sendMessage(alicesMessage).runAsync

    aliceReceived1.futureValue shouldBe bobsMessage
    recoverToSucceededIf[IllegalStateException](aliceReceived2)
  }

  it should "shutdown cleanly" in {
    val pg1 = dtlsPeerGroup[String](rawKeyConfig("alice"))
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runAsync.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }
}

object DTLSPeerGroupSpec {

  def rawKeyConfig(alias: String): Config = {
    Unauthenticated(aRandomAddress(), certAt(alias).getPublicKey, keyAt(alias))
  }

  def signedCertConfig(alias: String): Config = {
    import scala.collection.JavaConverters._
    val fact = CertificateFactory.getInstance("X.509")
    val certChain: Array[Certificate] = fact
      .generateCertificates(DTLSPeerGroupSpec.getClass.getClassLoader.getResourceAsStream(s"${alias}.pem"))
      .asScala
      .toArray

    CertAuthenticated(aRandomAddress(), certChain, keyAt(alias), NetUtils.trustedCerts)
  }

  def withTwoDTLSPeerGroups[M](cgens: (String => Config)*)(
      testCode: (DTLSPeerGroup[M], DTLSPeerGroup[M]) => Any
  )(implicit codec: Codec[M], bufferInstantiator: BufferInstantiator[ByteBuffer]): Unit = cgens.foreach { cgen =>
    val pg1 = dtlsPeerGroup[M](cgen("alice"))
    val pg2 = dtlsPeerGroup[M](cgen("bob"))
    println(s"Alice is ${pg1.processAddress}")
    println(s"Bob is ${pg2.processAddress}")
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }



  def dtlsPeerGroup[M](
      config: Config
  )(implicit codec: Codec[M], bufferInstantiator: BufferInstantiator[ByteBuffer]): DTLSPeerGroup[M] = {
    val pg = new DTLSPeerGroup[M](config)
    Await.result(pg.initialize().runAsync, Duration.Inf)
    pg
  }

  def keyAt(alias: String): PrivateKey = {
    NetUtils.keyStore.getKey(alias, "password".toCharArray).asInstanceOf[PrivateKey]
  }

  def certAt(alias: String): Certificate = {
    NetUtils.keyStore.getCertificate(alias)
  }
}
