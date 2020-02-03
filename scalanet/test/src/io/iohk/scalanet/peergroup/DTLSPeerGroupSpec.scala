package io.iohk.scalanet.peergroup

import java.security.PrivateKey
import java.security.cert.{Certificate, CertificateFactory}

import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.NetUtils.{aRandomAddress, isListeningUDP}
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.DTLSPeerGroup.Config
import io.iohk.scalanet.peergroup.DTLSPeerGroup.Config._
import io.iohk.scalanet.peergroup.DTLSPeerGroupSpec._
import io.iohk.scalanet.peergroup.PeerGroup.{HandshakeException, MessageMTUException}
import io.iohk.scalanet.peergroup.StandardTestPack.{messagingTest, serverMultiplexingTest}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.RecoverMethods._
import org.scalatest.concurrent.ScalaFutures._
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs.implicits._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, _}

class DTLSPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "DTLSPeerGroup"

  it should "report an error for a handshake failure -- server receives" in
    withTwoDTLSPeerGroups[String](duffKeyConfig) { (alice, bob) =>
      val handshakeF = bob.server().collectHandshakeFailure.headL.runToFuture

      alice.client(bob.processAddress).evaluated.sendMessage("hello, bob").runToFuture
      bob.server().connect()
      handshakeF.futureValue.to shouldBe alice.processAddress
    }

  it should "report an error for a handshake failure -- client receives" in
    withTwoDTLSPeerGroups[ByteVector](duffKeyConfig) { (alice, bob) =>
      val alicesMessage = ByteVector(NetUtils.randomBytes(1500))

      val aliceClient = alice.client(bob.processAddress).evaluated

      val error = recoverToExceptionIf[HandshakeException[InetMultiAddress]] {
        aliceClient.sendMessage(alicesMessage).runToFuture
      }.futureValue

      error.to shouldBe bob.processAddress
    }

  it should "report an error for sending a message greater than the MTU" in
    withADTLSPeerGroup[ByteVector](rawKeyConfig) { alice =>
      val address = InetMultiAddress(NetUtils.aRandomAddress())
      val invalidMessage = ByteVector(NetUtils.randomBytes(16584))
      val messageSize = Codec[ByteVector].encode(invalidMessage).require.toByteBuffer.capacity()

      val error = recoverToExceptionIf[MessageMTUException[InetMultiAddress]] {
        alice.client(address).flatMap(channel => channel.sendMessage(invalidMessage)).runToFuture
      }.futureValue

      error.size shouldBe messageSize
    }

  it should "send and receive a message" in
    withTwoDTLSPeerGroups[String](rawKeyConfig, signedCertConfig) { (alice, bob) =>
      messagingTest(alice, bob)
    }

  it should "do multiplexing properly" in
    withTwoDTLSPeerGroups[String](rawKeyConfig) { (alice, bob) =>
      serverMultiplexingTest(alice, bob)
    }

  it should "shutdown cleanly" in {
    val pg1 = dtlsPeerGroup[String](rawKeyConfig("alice"))
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runToFuture.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }

  it should "throw InitializationError when port already in use" in {
    val config = rawKeyConfig("alice")
    val pg1 = new DTLSPeerGroup[String](config)
    val pg2 = new DTLSPeerGroup[String](config)

    Await.result(pg1.initialize().runToFuture, Duration.Inf)
    assertThrows[InitializationError] {
      Await.result(pg2.initialize().runToFuture, Duration.Inf)
    }
    pg1.shutdown().runToFuture.futureValue

  }
}

object DTLSPeerGroupSpec {

  def rawKeyConfig(alias: String): Config = {
    Unauthenticated(aRandomAddress(), certAt(alias).getPublicKey, keyAt(alias))
  }

  def duffKeyConfig(alias: String): Config = {
    Unauthenticated(aRandomAddress(), certAt("alice").getPublicKey, keyAt("bob"))
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
  )(implicit codec: Codec[M]): Unit = cgens.foreach { cgen =>
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

  def withADTLSPeerGroup[M](cgens: (String => Config)*)(
      testCode: DTLSPeerGroup[M] => Any
  )(implicit codec: Codec[M]): Unit = cgens.foreach { cgen =>
    val pg = dtlsPeerGroup[M](cgen("alice"))
    try {
      testCode(pg)
    } finally {
      pg.shutdown()
    }
  }

  def dtlsPeerGroup[M](
      config: Config
  )(implicit codec: Codec[M]): DTLSPeerGroup[M] = {
    val pg = new DTLSPeerGroup[M](config)
    Await.result(pg.initialize().runToFuture, Duration.Inf)
    pg
  }

  def keyAt(alias: String): PrivateKey = {
    NetUtils.keyStore.getKey(alias, "password".toCharArray).asInstanceOf[PrivateKey]
  }

  def certAt(alias: String): Certificate = {
    NetUtils.keyStore.getCertificate(alias)
  }
}
