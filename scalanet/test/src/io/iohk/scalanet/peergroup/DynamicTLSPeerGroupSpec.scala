package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.security.SecureRandom

import cats.effect.concurrent.Deferred
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.codec.FramingCodec
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.crypto.CryptoUtils.Secp256r1
import io.iohk.scalanet.peergroup.Channel.{DecodingError, MessageReceived}
import io.iohk.scalanet.peergroup.DynamicTLSPeerGroupSpec._
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelBrokenException, ChannelSetupException, HandshakeException}
import io.iohk.scalanet.peergroup.ReqResponseProtocol.DynamicTLS
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.SignedKey
import io.iohk.scalanet.peergroup.dynamictls.{DynamicTLSExtension, DynamicTLSPeerGroup, Secp256k1}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.{Config, PeerInfo}
import io.iohk.scalanet.testutils.GeneratorUtils
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{Assertion, AsyncFlatSpec, BeforeAndAfterAll}
import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs.implicits._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

class DynamicTLSPeerGroupSpec extends AsyncFlatSpec with BeforeAndAfterAll {
  val timeOutConfig = 5.seconds
  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5.seconds)
  implicit val codec = new FramingCodec(Codec[String])
  implicit val scheduler = Scheduler.fixedPool("test", 16)

  behavior of "Dynamic TLSPeerGroup"

  it should "handshake successfully" in taskTestCase {
    val client = new DynamicTLSPeerGroup[String](getCorrectConfig())
    val server = new DynamicTLSPeerGroup[String](getCorrectConfig())

    for {
      _ <- client.initialize()
      _ <- server.initialize()
      ch1 <- client.client(server.processAddress)
      _ <- ch1.sendMessage("Hello enc server")
      rec <- server.server().refCount.collectChannelCreated.mergeMap(ch => ch.in.refCount).headL
    } yield {
      rec shouldEqual MessageReceived("Hello enc server")
    }
  }

  it should "closing client should inform server about closeup" in taskTestCase {
    val client = new DynamicTLSPeerGroup[String](getCorrectConfig())
    val server = new DynamicTLSPeerGroup[String](getCorrectConfig())

    for {
      d <- Deferred[Task, Boolean]
      _ <- client.initialize()
      _ <- server.initialize()
      clientChannel <- client.client(server.processAddress)
      serverChannel <- server.server().refCount.collectChannelCreated.headL
      _ <- serverChannel.in.refCount.guarantee(d.complete(true)).foreachL(a => ()).startAndForget
      _ <- clientChannel.close()
      closed <- d.get.timeout(timeOutConfig).onErrorHandle(_ => false)
    } yield {
      assert(closed)
    }
  }

  it should "closing server should inform client about closeup" in taskTestCase {
    val client = new DynamicTLSPeerGroup[String](getCorrectConfig())
    val server = new DynamicTLSPeerGroup[String](getCorrectConfig())

    for {
      d <- Deferred[Task, Boolean]
      _ <- client.initialize()
      _ <- server.initialize()
      ch1 <- client.client(server.processAddress)
      serverCh <- server.server().refCount.collectChannelCreated.headL
      _ <- ch1.in.refCount.guarantee(d.complete(true)).foreachL(a => ()).startAndForget
      _ <- serverCh.close()
      closed <- d.get.timeout(timeOutConfig).onErrorHandle(_ => false)
    } yield {
      assert(closed)
    }
  }

  it should "send/receive large messages received in parralel from different channels" in taskTestCase {
    val randomString1 = Random.nextString(50000)
    val randomString2 = Random.nextString(50000)
    val randomString3 = Random.nextString(50000)

    for {
      client1 <- DynamicTLS.getProtocol[String](aRandomAddress())
      client2 <- DynamicTLS.getProtocol[String](aRandomAddress())
      client3 <- DynamicTLS.getProtocol[String](aRandomAddress())
      server <- DynamicTLS.getProtocol[String](aRandomAddress())
      _ <- server.startHandling(s => s).startAndForget
      responses1 <- Task.parZip3(
        client1.send(randomString1, server.processAddress),
        client2.send(randomString2, server.processAddress),
        client3.send(randomString3, server.processAddress)
      )
      (resp1a, resp1b, resp1c) = responses1
    } yield {
      resp1a shouldEqual randomString1
      resp1b shouldEqual randomString2
      resp1c shouldEqual randomString3
    }
  }

  it should "fail to connect to offline peer" in taskTestCase {
    val client = new DynamicTLSPeerGroup[String](getCorrectConfig())
    val server = new DynamicTLSPeerGroup[String](getCorrectConfig())
    for {
      _ <- client.initialize()
      ch1 <- client.client(server.processAddress).attempt
    } yield {
      ch1.isLeft shouldEqual true
      ch1.left.get shouldBe a[ChannelSetupException[_]]
    }
  }

  it should "report error when trying to send message through closed channel" in taskTestCase {
    val client = new DynamicTLSPeerGroup[String](getCorrectConfig())
    val server = new DynamicTLSPeerGroup[String](getCorrectConfig())
    for {
      _ <- client.initialize()
      _ <- server.initialize()
      ch1 <- client.client(server.processAddress)
      _ <- server.shutdown()
      result <- ch1.sendMessage("wow").attempt
    } yield {
      result.isLeft shouldEqual true
      result.left.get shouldBe a[ChannelBrokenException[_]]
    }
  }

  it should "handle ssl handshake failure becouse of server" in taskTestCase {
    val client = new DynamicTLSPeerGroup[String](getCorrectConfig())
    val server = new DynamicTLSPeerGroup[String](getIncorrectConfigWrongId())

    for {
      _ <- client.initialize()
      _ <- server.initialize()
      ch1 <- client.client(server.processAddress).attempt
      serverHandshake <- server.server().refCount.collectHandshakeFailure.headL
    } yield {
      ch1.isLeft shouldEqual true
      ch1.left.get shouldBe a[HandshakeException[_]]
      serverHandshake shouldBe a[HandshakeException[_]]
    }
  }

  it should "handle ssl handshake failure becouse of client" in taskTestCase {
    val client = new DynamicTLSPeerGroup[String](getIncorrectConfigWrongSig())
    val server = new DynamicTLSPeerGroup[String](getCorrectConfig())

    for {
      _ <- client.initialize()
      _ <- server.initialize()
      ch1 <- client.client(server.processAddress).attempt
      serverHandshake <- server.server().refCount.collectHandshakeFailure.headL
    } yield {
      ch1.isLeft shouldEqual true
      ch1.left.get shouldBe a[HandshakeException[_]]
      serverHandshake shouldBe a[HandshakeException[_]]
    }
  }

  it should "handle inform user about decoding error" in taskTestCase {
    implicit val s = new FramingCodec[TestMessage[String]](Codec[TestMessage[String]])
    val client = new DynamicTLSPeerGroup[String](getCorrectConfig())
    val server = new DynamicTLSPeerGroup[TestMessage[String]](getCorrectConfig())

    for {
      _ <- client.initialize()
      _ <- server.initialize()
      ch1 <- client.client(server.processAddress)
      result <- Task.parZip2(
        ch1.sendMessage("Hello server, do not process this message"),
        server.server().refCount.collectChannelCreated.mergeMap(_.in.refCount).headL
      )
      (_, eventReceived) = result
    } yield {
      assert(eventReceived == DecodingError)
    }
  }
}

object DynamicTLSPeerGroupSpec {
  def taskTestCase(t: => Task[Assertion])(implicit s: Scheduler): Future[Assertion] =
    t.runToFuture

  val rnd = new SecureRandom()

  def getCorrectConfig(address: InetSocketAddress = aRandomAddress()): DynamicTLSPeerGroup.Config = {
    val hostkeyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
    Config(address, Secp256k1, hostkeyPair, rnd).get
  }

  def getIncorrectConfigWrongId(address: InetSocketAddress = aRandomAddress()): DynamicTLSPeerGroup.Config = {
    val correctConfig = getCorrectConfig(address)
    correctConfig.copy(peerInfo = correctConfig.peerInfo.copy(id = BitVector(1, 0, 1, 2)))
  }

  def getIncorrectConfigWrongSig(address: InetSocketAddress = aRandomAddress()): DynamicTLSPeerGroup.Config = {
    val validInterval = DynamicTLSExtension.getInterval()

    val hostkeyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)

    val connectionKeyPair = CryptoUtils.genTlsSupportedKeyPair(rnd, Secp256r1)

    val fakeKey = BitVector(GeneratorUtils.byteArrayOfNItemsGen(64).sample.get)

    val (sig, extension) = SignedKey.buildSignedKeyExtension(Secp256k1, hostkeyPair, fakeKey, rnd).require

    val cer = CryptoUtils.buildCertificateWithExtensions(
      connectionKeyPair,
      rnd,
      List(extension),
      validInterval.getStart.toDate,
      validInterval.getEnd.toDate
    )

    DynamicTLSPeerGroup.Config(
      address,
      PeerInfo(sig.publicKey.getNodeId, InetMultiAddress(address)),
      connectionKeyPair,
      cer
    )
  }

}
