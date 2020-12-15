package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.security.SecureRandom
import cats.implicits._
import cats.effect.concurrent.Deferred
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.codec.FramingCodec
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.crypto.CryptoUtils.Secp256r1
import io.iohk.scalanet.peergroup.implicits._
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
    (for {
      client <- DynamicTLSPeerGroup[String](getCorrectConfig())
      server <- DynamicTLSPeerGroup[String](getCorrectConfig())
      ch1 <- client.client(server.processAddress)
    } yield (server, ch1)).use {
      case (server, ch1) =>
        for {
          _ <- ch1.sendMessage("Hello enc server")
          rec <- server.serverEventObservable.collectChannelCreated.mergeMap {
            case (ch, release) => ch.channelEventObservable.guarantee(release)
          }.headL
        } yield {
          rec shouldEqual MessageReceived("Hello enc server")
        }
    }
  }

  it should "closing client should inform server about closeup" in taskTestCase {
    (for {
      client <- DynamicTLSPeerGroup[String](getCorrectConfig())
      server <- DynamicTLSPeerGroup[String](getCorrectConfig())
    } yield (client, server)).use {
      case (client, server) =>
        for {
          d <- Deferred[Task, Boolean]
          clientChannel <- client.client(server.processAddress).allocated
          serverChannel <- server.serverEventObservable.collectChannelCreated.headL
          _ <- serverChannel._1.channelEventObservable.guarantee(d.complete(true)).foreachL(_ => ()).startAndForget
          _ <- clientChannel._2
          closed <- d.get.timeout(timeOutConfig).onErrorHandle(_ => false)
          _ <- serverChannel._2
        } yield {
          assert(closed)
        }
    }
  }

  it should "closing server should inform client about closeup" in taskTestCase {
    (for {
      client <- DynamicTLSPeerGroup[String](getCorrectConfig())
      server <- DynamicTLSPeerGroup[String](getCorrectConfig())
    } yield (client, server)).use {
      case (client, server) =>
        for {
          d <- Deferred[Task, Boolean]
          clientChannel <- client.client(server.processAddress).allocated
          serverChannel <- server.serverEventObservable.collectChannelCreated.headL
          _ <- clientChannel._1.channelEventObservable.guarantee(d.complete(true)).foreachL(_ => ()).startAndForget
          _ <- serverChannel._2
          closed <- d.get.timeout(timeOutConfig).onErrorHandle(_ => false)
          _ <- clientChannel._2
        } yield {
          assert(closed)
        }
    }
  }

  it should "handle messages in the order they are sent" in taskTestCase {
    (for {
      server <- DynamicTLSPeerGroup[String](getCorrectConfig())
      client <- DynamicTLSPeerGroup[String](getCorrectConfig())
      channel <- client.client(server.processAddress)
    } yield (server, channel)).use {
      case (server, clientChannel) =>
        for {
          serverChannelCreated <- server.serverEventObservable.collectChannelCreated.headL
          (serverChannel, release) = serverChannelCreated
          messages = List.range(0, 100).map(_.toString)
          _ <- messages.traverse(clientChannel.sendMessage)
          received <- serverChannel.channelEventObservable
            .collect {
              case MessageReceived(msg) => msg
            }
            .take(100)
            .toListL
            .guarantee(release)
        } yield {
          assert(received == messages)
        }
    }
  }

  it should "send/receive large messages received in parallel from different channels" in taskTestCase {
    val randomString1 = Random.nextString(50000)
    val randomString2 = Random.nextString(50000)
    val randomString3 = Random.nextString(50000)

    List.fill(4)(DynamicTLS.getProtocol[String](aRandomAddress())).sequence.use {
      case List(client1, client2, client3, server) =>
        for {
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
  }

  it should "fail to connect to offline peer" in taskTestCase {
    val serverConfig = getCorrectConfig()
    DynamicTLSPeerGroup[String](getCorrectConfig()).use { client =>
      client.client(serverConfig.peerInfo).use(_ => Task.unit).attempt.map { result =>
        result.isLeft shouldEqual true
        result.left.get shouldBe a[ChannelSetupException[_]]
      }
    }
  }

  it should "report error when trying to send message through closed channel" in taskTestCase {
    DynamicTLSPeerGroup[String](getCorrectConfig()).use { client =>
      DynamicTLSPeerGroup[String](getCorrectConfig()).allocated.flatMap {
        case (server, release) =>
          client.client(server.processAddress).use { ch1 =>
            for {
              _ <- release
              result <- ch1.sendMessage("wow").attempt
            } yield {
              result.isLeft shouldEqual true
              result.left.get shouldBe a[ChannelBrokenException[_]]
            }
          }
      }
    }
  }

  it should "handle ssl handshake failure because of server" in taskTestCase {
    (for {
      client <- DynamicTLSPeerGroup[String](getCorrectConfig())
      server <- DynamicTLSPeerGroup[String](getIncorrectConfigWrongId())
    } yield (client, server)).use {
      case (client, server) =>
        for {
          ch1 <- client.client(server.processAddress).use(_ => Task.unit).attempt
          serverHandshake <- server.serverEventObservable.collectHandshakeFailure.headL
        } yield {
          ch1.isLeft shouldEqual true
          ch1.left.get shouldBe a[HandshakeException[_]]
          serverHandshake shouldBe a[HandshakeException[_]]
        }
    }
  }

  it should "handle ssl handshake failure because of client" in taskTestCase {
    (for {
      client <- DynamicTLSPeerGroup[String](getIncorrectConfigWrongSig())
      server <- DynamicTLSPeerGroup[String](getCorrectConfig())
    } yield (client, server)).use {
      case (client, server) =>
        for {
          ch1 <- client.client(server.processAddress).use(_ => Task.unit).attempt
          serverHandshake <- server.serverEventObservable.collectHandshakeFailure.headL
        } yield {
          ch1.isLeft shouldEqual true
          ch1.left.get shouldBe a[HandshakeException[_]]
          serverHandshake shouldBe a[HandshakeException[_]]
        }
    }
  }

  it should "inform user about decoding error" in taskTestCase {
    implicit val s = new FramingCodec[TestMessage[String]](Codec[TestMessage[String]])
    (for {
      client <- DynamicTLSPeerGroup[String](getCorrectConfig())
      server <- DynamicTLSPeerGroup[TestMessage[String]](getCorrectConfig())
      ch1 <- client.client(server.processAddress)
    } yield (client, server, ch1)).use {
      case (client, server, ch1) =>
        for {
          result <- Task.parZip2(
            ch1.sendMessage("Hello server, do not process this message"),
            server.serverEventObservable.collectChannelCreated.mergeMap {
              case (channel, release) =>
                channel.channelEventObservable.guarantee(release)
            }.headL
          )
          (_, eventReceived) = result
        } yield {
          assert(eventReceived == DecodingError)
        }
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
