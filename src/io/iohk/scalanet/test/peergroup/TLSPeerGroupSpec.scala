package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer
import java.security.{KeyStore, PrivateKey}
import java.security.cert.{Certificate, CertificateFactory}

import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.TaskValues._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import io.iohk.scalanet.peergroup.TLSPeerGroup._
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.{BufferInstantiator, Codec}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Random
import TLSPeerGroupSpec._
import io.netty.handler.ssl.util.SelfSignedCertificate
class TLSPeerGroupSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5 seconds)

  behavior of "TLSPeerGroup"
  val clientAuth: Seq[Boolean] = Seq(true, false)

  it should "send and receive a message when client auth is disabled/false or enabled/true" in clientAuth

  for (value <- clientAuth) {
    withTwoRandomTLSPeerGroups[String](value) { (alice, bob) =>
      println(s"Alice is ${alice.processAddress}, bob is ${bob.processAddress}")
      val alicesMessage = Random.alphanumeric.take(1024).mkString
      val bobsMessage = Random.alphanumeric.take(1024).mkString

      bob.server().foreachL(channel => channel.sendMessage(bobsMessage).runAsync).runAsync
      val bobReceived: Future[String] = bob.server().mergeMap(channel => channel.in).headL.runAsync
      val aliceClient = alice.client(bob.processAddress).evaluated
      val aliceReceived = aliceClient.in.headL.runAsync

      aliceClient.sendMessage(alicesMessage).evaluated

      bobReceived.futureValue shouldBe alicesMessage
      aliceReceived.futureValue shouldBe bobsMessage
    }

  }

  it should "send and receive a message" in {
    withTwoTLSPeerGroups[String](selfSignedCertConfig, selfSignedCertConfig) { (alice, bob) =>
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

  it should "shutdown a TLSPeerGroup properly" in {
    val tlsPeerGroup = randomTLSPeerGroup[String]
    isListening(tlsPeerGroup.config.bindAddress) shouldBe true

    tlsPeerGroup.shutdown().runAsync.futureValue

    isListening(tlsPeerGroup.config.bindAddress) shouldBe false
  }

  it should "report the same address for two inbound channels" in
    withTwoRandomTLSPeerGroups[String](false) { (alice, bob) =>
      val firstInbound = bob.server().headL.runAsync
      val secondInbound = bob.server().drop(1).headL.runAsync

      alice.client(bob.processAddress).evaluated
      alice.client(bob.processAddress).evaluated

      firstInbound.futureValue.to shouldBe alice.processAddress
      secondInbound.futureValue.to shouldBe alice.processAddress
    }


}

object TLSPeerGroupSpec{

  def signedCertConfig(alias: String): Config = {
    import scala.collection.JavaConverters._
    val fact = CertificateFactory.getInstance("X.509")
    val certChain = fact
      .generateCertificates(DTLSPeerGroupSpec.getClass.getClassLoader.getResourceAsStream(s"${alias}.pem"))
      .asScala
      .toList

    Config(aRandomAddress(),keyAt(alias),certChain, NetUtils.trustedCerts.toList)
  }

  def selfSignedCertConfig(alias: String): Config = {

    val key = keyStore.getKey(alias,"password".toCharArray).asInstanceOf[PrivateKey]
    val certChain = keyStore.getCertificateChain(alias).toList
    val trustStore =  List(keyStore.getCertificate("bob"))
    Config(aRandomAddress(),key ,certChain, trustStore)

  }


  def withTwoTLSPeerGroups[M](cgens: (String => Config)*)(
    testCode: (TLSPeerGroup[M], TLSPeerGroup[M]) => Any
  )(implicit codec: Codec[M], bufferInstantiator: BufferInstantiator[ByteBuffer]): Unit = cgens.foreach { cgen =>
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

  def tlsPeerGroup[M](config: Config)(implicit codec: Codec[M], bufferInstantiator: BufferInstantiator[ByteBuffer]): TLSPeerGroup[M] = {
    val pg = new TLSPeerGroup[M](config)
    Await.result(pg.initialize().runAsync, Duration.Inf)
    pg
  }

  val keyStore = {
    val keyStore = KeyStore.getInstance("JKS")
    keyStore.load(null)
    val aliceSc = new SelfSignedCertificate()
    val bobSc = new SelfSignedCertificate()

    keyStore.setCertificateEntry("alice", bobSc.cert())
    keyStore.setKeyEntry("alice",aliceSc.key(),"password".toCharArray,Array(aliceSc.cert()))

    keyStore.setCertificateEntry("bob", aliceSc.cert())
    keyStore.setKeyEntry("bob",bobSc.key(),"password".toCharArray,Array(bobSc.cert()))

    keyStore
  }


  def keyAt(alias: String): PrivateKey = {
    NetUtils.keyStore.getKey(alias, "password".toCharArray).asInstanceOf[PrivateKey]
  }

  def certAt(alias: String): Certificate = {
    NetUtils.keyStore.getCertificate(alias)
  }
}
