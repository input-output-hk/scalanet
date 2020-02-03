package io.iohk.scalanet.peergroup

import java.security.PrivateKey
import java.security.cert.{Certificate, CertificateFactory}

import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.codec.{FramingCodec, StreamCodec}
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelBrokenException, HandshakeException}
import io.iohk.scalanet.peergroup.StandardTestPack.messagingTest
import io.iohk.scalanet.peergroup.TLSPeerGroup._
import io.iohk.scalanet.peergroup.TLSPeerGroupSpec._
import io.netty.handler.ssl.util.SelfSignedCertificate
import monix.execution.CancelableFuture
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.RecoverMethods.recoverToExceptionIf
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import scodec.Codec
import scodec.codecs.implicits.implicitStringCodec

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

class TLSPeerGroupSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5 seconds)
  implicit val codec = new FramingCodec(Codec[String])

  behavior of "TLSPeerGroup"

  it should "report an error for a handshake failure -- server receives" in
    withTwoTLSPeerGroups[String](duffKeyConfig) { (alice, bob) =>
      val handshakeF = bob.server().collectHandshakeFailure.headL.runToFuture
      alice.client(bob.processAddress).runToFuture
      bob.server().connect()

      handshakeF.futureValue.to shouldBe alice.processAddress
    }

  it should "report an error for a handshake failure -- client receives" in
    withTwoTLSPeerGroups[String](duffKeyConfig) { (alice, bob) =>
      val error = recoverToExceptionIf[HandshakeException[InetMultiAddress]] {
        alice.client(bob.processAddress).runToFuture
      }.futureValue

      error.to shouldBe bob.processAddress
    }

  it should "report an error for messaging to an invalid address" in
    withATLSPeerGroup[String](selfSignedCertConfig) { alice =>
      StandardTestPack.shouldErrorForMessagingAnInvalidAddress(alice, InetMultiAddress(NetUtils.aRandomAddress()))
    }

  // TODO this is a copy/paste version of the test in TCPPeerGroupSpec
  it should "report an error for messaging on a closed channel -- server closes" in
    withTwoTLSPeerGroups[String](selfSignedCertConfig) { (alice, bob) =>
      val alicesMessage = Random.alphanumeric.take(1024).mkString
      val bobsChannelF = bob.server().collectChannelCreated.headL.runToFuture
      bob.server().collectChannelCreated.foreach(_.in.connect())
      bob.server().connect()
      val aliceClient = alice.client(bob.processAddress).evaluated
      bobsChannelF.futureValue.close().evaluated
      val aliceError = recoverToExceptionIf[ChannelBrokenException[InetMultiAddress]] {
        aliceClient.sendMessage(alicesMessage).runToFuture
      }

      aliceError.futureValue.to shouldBe bob.processAddress
    }

  // TODO this is a copy/paste version of the test in TCPPeerGroupSpec
  it should "report an error for messaging on a closed channel -- client closes" in
    withTwoTLSPeerGroups[String](selfSignedCertConfig) { (alice, bob) =>
      val bobsMessage = Random.alphanumeric.take(1024).mkString
      bob.server().collectChannelCreated.foreachL(channel => channel.sendMessage(bobsMessage).runToFuture).runToFuture
      bob.server().collectChannelCreated.foreach(_.in.connect())
      bob.server().connect()
      val bobChannel: CancelableFuture[Channel[InetMultiAddress, String]] =
        bob.server().collectChannelCreated.headL.runToFuture

      val aliceClient = alice.client(bob.processAddress).evaluated
      aliceClient.close().evaluated
      val bobError = recoverToExceptionIf[ChannelBrokenException[InetMultiAddress]] {
        bobChannel.futureValue.sendMessage(bobsMessage).runToFuture
      }

      bobError.futureValue.to shouldBe alice.processAddress
    }

  it should "send and receive a message" in withTwoTLSPeerGroups[String](selfSignedCertConfig, signedCertConfig) {
    (alice, bob) =>
      messagingTest(alice, bob)
  }

  // TODO this is a copy/paste version of the test in TCPPeerGroupSpec
  it should "shutdown a TLSPeerGroup properly" in {
    val tlsPeerGroup = randomTLSPeerGroup[String]
    isListening(tlsPeerGroup.config.bindAddress) shouldBe true

    tlsPeerGroup.shutdown().runToFuture.futureValue

    isListening(tlsPeerGroup.config.bindAddress) shouldBe false
  }
  // TODO this is a copy/paste version of the test in TCPPeerGroupSpec
  it should "report the same address for two inbound channels" in
    withTwoRandomTLSPeerGroups[String](false) { (alice, bob) =>
      val firstInbound = bob.server().collectChannelCreated.headL.runToFuture
      val secondInbound = bob.server().collectChannelCreated.drop(1).headL.runToFuture
      bob.server().collectChannelCreated.foreach(_.in.connect())
      bob.server().connect()
      alice.client(bob.processAddress).evaluated
      alice.client(bob.processAddress).evaluated

      firstInbound.futureValue.to shouldBe alice.processAddress
      secondInbound.futureValue.to shouldBe alice.processAddress
    }

  it should "throw InitializationError when port already in use" in {
    val address = aRandomAddress()
    val sc1 = new SelfSignedCertificate()
    val sc2 = new SelfSignedCertificate()

    val pg1 = new TLSPeerGroup(TLSPeerGroup.Config(address, sc1.key(), List(sc1.cert()), List(sc2.cert()), false))
    val pg2 = new TLSPeerGroup(TLSPeerGroup.Config(address, sc2.key(), List(sc2.cert()), List(sc1.cert()), false))

    Await.result(pg1.initialize().runToFuture, 10 seconds)
    assertThrows[InitializationError] {
      Await.result(pg2.initialize().runToFuture, 10 seconds)
    }
    pg1.shutdown().runToFuture.futureValue

  }

}

object TLSPeerGroupSpec {

  def duffKeyConfig(alias: String): Config = {
    val key: PrivateKey = keyStore.getKey("alice", "password".toCharArray).asInstanceOf[PrivateKey]
    val trustStore = List(keyStore.getCertificate("bob"))
    val certChain = trustStore
    Config(aRandomAddress(), key, certChain, trustStore)
  }

  def signedCertConfig(alias: String): Config = {
    import scala.collection.JavaConverters._
    val fact = CertificateFactory.getInstance("X.509")
    val certChain = fact
      .generateCertificates(DTLSPeerGroupSpec.getClass.getClassLoader.getResourceAsStream(s"${alias}.pem"))
      .asScala
      .toList

    Config(aRandomAddress(), keyAt(alias), certChain, NetUtils.trustedCerts.toList)
  }

  def selfSignedCertConfig(alias: String): Config = {
    val key = keyStore.getKey(alias, "password".toCharArray).asInstanceOf[PrivateKey]
    val certChain = keyStore.getCertificateChain(alias).toList
    val trustStore = List(keyStore.getCertificate("bob"))
    Config(aRandomAddress(), key, certChain, trustStore)
  }

  def withTwoTLSPeerGroups[M](cgens: (String => Config)*)(
      testCode: (TLSPeerGroup[M], TLSPeerGroup[M]) => Any
  )(implicit codec: StreamCodec[M]): Unit = cgens.foreach { cgen =>
    val pg1 = tlsPeerGroup[M](cgen("alice"))
    val pg2 = tlsPeerGroup[M](cgen("bob"))
    println(s"Alice is ${pg1.processAddress}")
    println(s"Bob is ${pg2.processAddress}")
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }

  def withATLSPeerGroup[M](cgens: (String => Config)*)(
      testCode: TLSPeerGroup[M] => Any
  )(implicit codec: StreamCodec[M]): Unit = cgens.foreach { cgen =>
    val pg = tlsPeerGroup[M](cgen("alice"))
    try {
      testCode(pg)
    } finally {
      pg.shutdown()
    }
  }

  def tlsPeerGroup[M](
      config: Config
  )(implicit codec: StreamCodec[M]): TLSPeerGroup[M] = {
    val pg = new TLSPeerGroup[M](config)
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
