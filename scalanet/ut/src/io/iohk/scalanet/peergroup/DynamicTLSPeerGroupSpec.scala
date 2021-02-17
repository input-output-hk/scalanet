package io.iohk.scalanet.peergroup

import cats.effect.Resource

import java.net.InetSocketAddress
import java.security.SecureRandom
import cats.implicits._
import cats.effect.concurrent.Deferred
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.crypto.CryptoUtils.{SHA256withECDSA, Secp256r1}
import io.iohk.scalanet.peergroup.implicits._
import io.iohk.scalanet.peergroup.Channel.{ChannelEvent, DecodingError, MessageReceived}
import io.iohk.scalanet.peergroup.DynamicTLSPeerGroupSpec._
import io.iohk.scalanet.peergroup.PeerGroup.ProxySupport.Socks5Config
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.PeerGroup.{
  ChannelBrokenException,
  ChannelSetupException,
  HandshakeException,
  ProxySupport
}
import io.iohk.scalanet.peergroup.ReqResponseProtocol.DynamicTLS
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.SignedKey
import io.iohk.scalanet.peergroup.dynamictls.{
  DynamicTLSExtension,
  DynamicTLSPeerGroup,
  DynamicTLSPeerGroupInternals,
  Secp256k1
}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.{Config, FramingConfig, PeerInfo}
import io.iohk.scalanet.testutils.GeneratorUtils
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{Assertion, AsyncFlatSpec, BeforeAndAfterAll}
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs.implicits._
import sockslib.server.{SocksProxyServer, SocksProxyServerFactory}

import java.util.concurrent.TimeoutException
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random
import scala.annotation.nowarn

@nowarn
class DynamicTLSPeerGroupSpec extends AsyncFlatSpec with BeforeAndAfterAll {

  val timeOutConfig = 5.seconds
  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5.seconds)
  implicit val scheduler = Scheduler.fixedPool("test", 16)

  behavior of "Dynamic TLSPeerGroup"

  for (nc <- List(true, false))
    for (ns <- List(true, false))
      it should s"handshake successfully using ${if (nc) "native" else "JDK"} client TLS and ${if (ns) "native"
      else "JDK"} server TLS" in taskTestCase {
        (for {
          client <- DynamicTLSPeerGroup[String](getCorrectConfig(useNativeTlsImplementation = nc))
          server <- DynamicTLSPeerGroup[String](getCorrectConfig(useNativeTlsImplementation = ns))
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

  it should s"handshake successfully when using bouncy and jdk key encodings" in taskTestCase {
    (for {
      client <- DynamicTLSPeerGroup[String](getCorrectConfig())
      server <- DynamicTLSPeerGroup[String](getCorrectConfigBouncyCastle())
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

  it should "throttle incoming connections when configured" in taskTestCase {
    val throttlingDuration = 1.second
    val throttlingConfig = DynamicTLSPeerGroup.IncomingConnectionThrottlingConfig(
      throttleLocalhost = true,
      throttlingDuration = throttlingDuration
    )
    (for {
      clientGroup1 <- DynamicTLSPeerGroup[String](getCorrectConfig())
      clientGroup2 <- DynamicTLSPeerGroup[String](getCorrectConfig())
      serverGroup <- DynamicTLSPeerGroup[String](
        getCorrectConfig().copy(incomingConnectionsThrottling = Some(throttlingConfig))
      )
    } yield (serverGroup, clientGroup1, clientGroup2)).use {
      case (server, clientGroup1, clientGroup2) =>
        for {
          result1 <- clientGroup1.client(server.processAddress).allocated
          (ch1, release1) = result1
          // this connection will fail as clientGroup2 has the same address as clientGroup1 i.e Localhost
          result2 <- clientGroup2.client(server.processAddress).allocated.attempt
          _ <- Task.sleep(throttlingDuration + 1.second)
          //
          result3 <- clientGroup2.client(server.processAddress).allocated.attempt
          (ch2, release2) = result3.right.get
          _ <- Task.parZip2(release1, release2)
        } yield {
          assert(result2.isLeft)
          assert(result2.left.get.isInstanceOf[ChannelBrokenException[_]])
          assert(result3.isRight)
        }
    }
  }

  it should "handshake successfully via proxy" in taskTestCase {
    (for {
      client <- DynamicTLSPeerGroup[String](getCorrectConfig()).map(
        group => ProxySupport(Socks5Config(new InetSocketAddress("localhost", 1080), None))(group)
      )
      proxy <- buildProxyServer(1080)
      server <- DynamicTLSPeerGroup[String](getCorrectConfig())
      ch1 <- client.client(server.processAddress)
    } yield (server, ch1, proxy)).use {
      case (server, ch1, proxy) =>
        for {
          _ <- ch1.sendMessage("Hello enc server")
          wo <- server.serverEventObservable.collectChannelCreated.mergeMap {
            case (ch, release) =>
              Observable(ch).zip(ch.channelEventObservable.guarantee(release))

          }.headL
          sessions <- Task(proxy.getSessionManager.getManagedSessions)
          (chan, rec) = wo
        } yield {
          assert(sessions.size() == 1)
          val session = sessions.get(1.toLong)
          assert(session.getNetworkMonitor.getReceiveTCP > 0)
          assert(session.getNetworkMonitor.getSendTCP > 0)
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
      case _ => fail()
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
          ch1 <- client.client(server.processAddress).allocated.attempt
          serverHandshake <- server.serverEventObservable.collectHandshakeFailure.headL
          // In tls 1.3 clients complete the TLS handshake immediately after sending the certificate without waiting
          // for verification results from server
          _ <- Task(assert(ch1.isRight))
          (chan, release) = ch1.right.get
          clientSendResult <- chan.sendMessage("hello").attempt
          _ <- Task(assert(clientSendResult.isLeft))
          result = clientSendResult.left.get
          // validation of client certificate failed on server side, when clients try to sent data it will fail as server
          // closed connection
          _ <- Task(assert(result.isInstanceOf[ChannelBrokenException[_]]))
        } yield {
          serverHandshake shouldBe a[HandshakeException[_]]
        }
    }
  }

  it should "inform user about decoding error" in taskTestCase {
    implicit val s = Codec[TestMessage[String]]
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
  it should "handle messages with prepended messages id-s" in taskTestCase {
    implicit val s = Codec[TestMessage[String]]
    (for {
      client <- DynamicTLSPeerGroup[TestMessage[String]](getCorrectConfig())
      server <- DynamicTLSPeerGroup[TestMessage[String]](getCorrectConfig())
      ch1 <- client.client(server.processAddress)
    } yield (client, server, ch1)).use {
      case (client, server, ch1) =>
        for {
          result <- Task.parZip2(
            ch1.sendMessage(TestMessage.Foo("hello")),
            server.serverEventObservable.collectChannelCreated.mergeMap {
              case (channel, release) =>
                channel.channelEventObservable.guarantee(release)
            }.headL
          )
          (_, eventReceived) = result
        } yield {
          assert(eventReceived == MessageReceived(TestMessage.Foo("hello")))
        }
    }
  }

  it should "inform user about too large frame" in taskTestCase {
    (for {
      client <- DynamicTLSPeerGroup[Byte](getCorrectConfig())
      // 1byte message + 4 bytes length field gives frame of size 5, so maxFrameLength = 4 should end with error
      server <- DynamicTLSPeerGroup[Byte](getCorrectConfig(maxFrameLength = 4))
      ch1 <- client.client(server.processAddress)
    } yield (client, server, ch1)).use {
      case (client, server, ch1) =>
        for {
          result <- Task.parZip2(
            ch1.sendMessage(1.toByte),
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

  it should "not send another message when receive queue and both buffers are full " in backPressureTestCase(
    clientConfig = getCorrectConfig(maxFrameLength = 64 * 1024 * 1024),
    serverConfig = getCorrectConfig(maxQueueSize = 1, maxFrameLength = 64 * 1024 * 1024)
  ) {
    case (clientChannel, serverChannel) =>
      // we are using pretty large messages to be sure incoming and sending buffers will be full
      val m1 = SizeAbleMessage.genRandomOfsizeN(4 * 1024 * 1024)
      val m2 = SizeAbleMessage.genRandomOfsizeN(4 * 1024 * 1024)
      for {
        _ <- clientChannel.sendMessage(m1)
        _ <- Task.sleep(1.second)
        // snd buffer and rcv buffer are full, flushing message to snd buffer will be impossible
        sendResult <- clientChannel.sendMessage(m2).timeout(5.seconds).attempt
      } yield {
        assert(sendResult.isLeft)
        assert(sendResult.left.get.isInstanceOf[TimeoutException])
      }
  }

  it should "successfully flush buffer when it will be free on receiver side " in backPressureTestCase(
    clientConfig = getCorrectConfig(maxFrameLength = 64 * 1024 * 1024),
    serverConfig = getCorrectConfig(maxQueueSize = 1, maxFrameLength = 64 * 1024 * 1024)
  ) {
    case (clientChannel, serverChannel) =>
      // we are using pretty large messages to be sure incoming and sending buffers will be full
      val m1 = SizeAbleMessage.genRandomOfsizeN(4 * 1024 * 1024)
      val m2 = SizeAbleMessage.genRandomOfsizeN(4 * 1024 * 1024)
      for {
        finished <- Deferred.tryable[Task, Unit]
        _ <- clientChannel.sendMessage(m1)
        _ <- Task.sleep(1.second)
        // snd buffer and rcv buffer all full, flushing message will be impossible
        _ <- clientChannel.sendMessage(m2).doOnFinish(_ => finished.complete(())).startAndForget
        _ <- Task.sleep(2.second)
        check <- finished.tryGet
        // even after some time message is not sent as buffers are still full
        _ <- Task(assert(check.isEmpty))
        // sending message should finish once receiver will consume event on its side and make space and rcv buffer
        serverEvent1 <- Task.parMap2(finished.get, serverChannel.nextChannelEvent)((_, sEv) => sEv)
        serverEvent2 <- serverChannel.nextChannelEvent
      } yield {
        assert(serverEvent1.get == MessageReceived(m1))
        assert(serverEvent2.get == MessageReceived(m2))
      }
  }

  it should "get more bytes form rcv buffer as soon as incoming queue will be at least half empty" in backPressureTestCase(
    clientConfig = getCorrectConfig(maxFrameLength = 64 * 1024 * 1024),
    serverConfig = getCorrectConfig(maxQueueSize = 4, maxFrameLength = 64 * 1024 * 1024)
  ) {
    case (clientChannel, serverChannel) =>
      val srvChannelImpl =
        serverChannel.asInstanceOf[DynamicTLSPeerGroupInternals.DynamicTlsChannel[ChannelEvent[SizeAbleMessage]]]
      // we are using small messages to not fill up snd and rcv buffers
      val messagesToSend = (0 to 5).map(_ => SizeAbleMessage.genRandomOfsizeN(4 * 1024))
      val firstBatch = messagesToSend.take(4)
      val secondBatch = messagesToSend.takeRight(2)
      for {
        _ <- Task.traverse(firstBatch)(m => clientChannel.sendMessage(m))
        _ <- Task(srvChannelImpl.incomingQueueSize).restartUntil(qSize => qSize == firstBatch.size)
        _ <- Task.sleep(1.seconds)
        // last two messages will be stored in rcv buffer, becouse incoming queue is full
        _ <- Task.traverse(secondBatch)(m => clientChannel.sendMessage(m))
        _ <- Task.sleep(1.seconds)
        _ <- Task(assert(srvChannelImpl.incomingQueueSize == firstBatch.size))
        _ <- srvChannelImpl.nextChannelEvent.map(ev => assert(ev.get == MessageReceived(firstBatch(0))))
        _ <- Task(assert(srvChannelImpl.incomingQueueSize == firstBatch.size - 1))
        _ <- srvChannelImpl.nextChannelEvent.map(ev => assert(ev.get == MessageReceived(firstBatch(1))))
        // after reading first 2 messages, there place in queue for two new messages, so they are read from rcv buffer
        _ <- Task(srvChannelImpl.incomingQueueSize)
          .restartUntil(qSize => qSize == ((firstBatch.size - 2) + secondBatch.size))
        _ <- Task(assert(srvChannelImpl.incomingQueueSize == 4))
        // all received messages are in expected order
        _ <- srvChannelImpl.nextChannelEvent.map(ev => assert(ev.get == MessageReceived(firstBatch(2))))
        _ <- srvChannelImpl.nextChannelEvent.map(ev => assert(ev.get == MessageReceived(firstBatch(3))))
        _ <- srvChannelImpl.nextChannelEvent.map(ev => assert(ev.get == MessageReceived(secondBatch(0))))
        _ <- srvChannelImpl.nextChannelEvent.map(ev => assert(ev.get == MessageReceived(secondBatch(1))))
        finalAssert <- Task(assert(srvChannelImpl.incomingQueueSize == 0))
      } yield {
        finalAssert
      }
  }

  it should "communicate with slower consumer with small queue available" in backPressureTestCase(
    clientConfig = getCorrectConfig(maxFrameLength = 64 * 1024 * 1024),
    serverConfig = getCorrectConfig(maxQueueSize = 1, maxFrameLength = 64 * 1024 * 1024)
  ) {
    case (clientChannel, serverChannel) =>
      val srvChannelImpl =
        serverChannel.asInstanceOf[DynamicTLSPeerGroupInternals.DynamicTlsChannel[ChannelEvent[SizeAbleMessage]]]
      // large message so fill the buffer after each sent
      val messagesToSend = (0 to 24).map(_ => SizeAbleMessage.genRandomOfsizeN(4 * 1024 * 1024))
      val sendTask = Task.traverse(messagesToSend)(m => clientChannel.sendMessage(m))
      val receiveTask = serverChannel.nextChannelEvent
        .delayExecution(500.milliseconds)
        .toObservable
        .collect { case MessageReceived(m) => m }
        .take(messagesToSend.size)
        .toListL
      for {
        receivedMessages <- Task.parMap2(sendTask, receiveTask)((_, received) => received)
        _ <- Task(assert(messagesToSend == receivedMessages))
        finalQueueSize <- Task(
          serverChannel.asInstanceOf[DynamicTLSPeerGroupInternals.DynamicTlsChannel[_]].incomingQueueSize
        )
      } yield {
        assert(finalQueueSize == 0)
      }
  }

}

object DynamicTLSPeerGroupSpec {
  def taskTestCase(t: => Task[Assertion])(implicit s: Scheduler): Future[Assertion] =
    t.runToFuture

  def backPressureTestCase(clientConfig: DynamicTLSPeerGroup.Config, serverConfig: DynamicTLSPeerGroup.Config)(
      testFun: ((Channel[PeerInfo, SizeAbleMessage], Channel[PeerInfo, SizeAbleMessage])) => Task[Assertion]
  )(implicit s: Scheduler): Future[Assertion] = {
    (for {
      client <- DynamicTLSPeerGroup[SizeAbleMessage](clientConfig)
      server <- DynamicTLSPeerGroup[SizeAbleMessage](serverConfig)
      clientChannel <- client.client(server.processAddress)
      sChan = server.nextServerEvent.map { ev =>
        ev.collect {
          case ChannelCreated(channel, release) => (channel, release)
        }.get
      }
      serverChannel <- Resource.make(sChan)((chWithRelease) => chWithRelease._2).map(_._1)
    } yield (clientChannel, serverChannel)).use(testFun).runToFuture
  }

  val rnd = new SecureRandom()

  def getCorrectConfig(
      address: InetSocketAddress = aRandomAddress(),
      useNativeTlsImplementation: Boolean = false,
      maxFrameLength: Int = 192000,
      maxQueueSize: Int = 100
  ): DynamicTLSPeerGroup.Config = {
    val hostkeyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
    val framingConfig = FramingConfig.buildConfigWithStrippedLength(maxFrameLength, 4).get
    Config(address, Secp256k1, hostkeyPair, rnd, useNativeTlsImplementation, framingConfig, maxQueueSize, None).get
  }

  def getCorrectConfigBouncyCastle(
      address: InetSocketAddress = aRandomAddress(),
      useNativeTlsImplementation: Boolean = false,
      maxFrameLength: Int = 192000,
      maxQueueSize: Int = 100
  ): DynamicTLSPeerGroup.Config = {
    val hostkeyPair = CryptoUtils.generateKeyPair(rnd)
    val framingConfig = FramingConfig.buildConfigWithStrippedLength(maxFrameLength, 4).get
    Config(address, Secp256k1, hostkeyPair, rnd, useNativeTlsImplementation, framingConfig, maxQueueSize, None).get
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
      validInterval.getEnd.toDate,
      SHA256withECDSA
    )

    val framingConfig = FramingConfig.buildConfigWithStrippedLength(192000, 4).get

    DynamicTLSPeerGroup.Config(
      address,
      PeerInfo(sig.publicKey.getNodeId, InetMultiAddress(address)),
      connectionKeyPair,
      cer,
      useNativeTlsImplementation = false,
      framingConfig,
      100,
      None
    )
  }

  def buildProxyServer(port: Int): Resource[Task, SocksProxyServer] = {
    Resource.make {
      Task {
        val proxyServer = SocksProxyServerFactory.newNoAuthenticationServer(port)
        proxyServer.start()
        proxyServer
      }
    } { server =>
      Task(server.shutdown())
    }
  }

  case class SizeAbleMessage(vec: ByteVector)
  object SizeAbleMessage {
    implicit val messageCodec: Codec[SizeAbleMessage] =
      scodec.codecs.bytes.xmap(bytes => SizeAbleMessage(bytes), mess => mess.vec)

    def genRandomOfsizeN(n: Int): SizeAbleMessage = {
      val arr = new Array[Byte](n)
      Random.nextBytes(arr)
      SizeAbleMessage(ByteVector(arr))
    }
  }
}
