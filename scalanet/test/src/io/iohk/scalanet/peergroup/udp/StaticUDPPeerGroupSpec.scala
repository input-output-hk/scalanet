package io.iohk.scalanet.peergroup.udp

import cats.effect.Resource
import cats.implicits._
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import monix.execution.Scheduler
import monix.eval.Task
import io.iohk.scalanet.peergroup.PeerGroup
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.Channel.MessageReceived
import org.scalatest.Matchers
import scodec.Codec
import scodec.codecs.implicits._
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import io.iohk.scalanet.peergroup.Channel

class StaticUDPPeerGroupSpec extends UDPPeerGroupSpec("StaticUDPPeerGroup") with Matchers {
  import UDPPeerGroupSpec.runEchoServer

  val timeout = 15.seconds

  override def initUdpPeerGroup[M](
      address: InetSocketAddress
  )(implicit s: Scheduler, c: Codec[M]): Resource[Task, UDPPeerGroupSpec.TestGroup[M]] = {
    StaticUDPPeerGroup[M](StaticUDPPeerGroup.Config(address)).map { pg =>
      new PeerGroup[InetMultiAddress, M] {
        override def processAddress = pg.processAddress
        override def server = pg.server
        override def client(to: InetMultiAddress) = pg.client(to)
        def channelCount: Int = pg.channelCount.runSyncUnsafe()
      }
    }
  }

  def startCollectingMessages(timespan: FiniteDuration)(channel: Channel[InetMultiAddress, String]) =
    channel.in.refCount
      .collect {
        case MessageReceived(msg) => msg
      }
      .takeByTimespan(timespan)
      .toListL
      .start

  it should "use the server port when it opens client channels" in {
    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg2 <- initUdpPeerGroup[String]()
      client12 <- pg1.client(pg2.processAddress)
    } yield (pg1, pg2, client12))
      .use {
        case (pg1, pg2, client12) =>
          for {
            _ <- client12.sendMessage("Hola!")
            event <- pg2.server.refCount.collectChannelCreated.headL
          } yield {
            event._1.to shouldBe pg1.processAddress
          }
      }
      .runSyncUnsafe(timeout)
  }

  it should "re-emit a server event if a closed channel is re-activated" in {
    initUdpPeerGroup[String]().allocated
      .flatMap {
        case (pg2, pg2Release) =>
          (for {
            pg1 <- initUdpPeerGroup[String]()
            client12 <- pg1.client(pg2.processAddress)
          } yield (pg1, client12))
            .use {
              case (pg1, client12) =>
                val messageCounter = new CountDownLatch(3)
                for {
                  // Close incoming channel after two messages and collect which ports they came from.
                  // Further messages should result in another incoming channel being created.
                  listener <- pg2.server.refCount.collectChannelCreated
                    .mapEval {
                      case (channel, release) =>
                        channel.in.refCount
                          .collect {
                            case MessageReceived(_) =>
                              messageCounter.countDown()
                          }
                          .take(2)
                          .completedL
                          .guarantee(release)
                          // Have to do it in the background otherwise it would block the processing of incoming UDP packets..
                          .startAndForget
                          .as(channel.to)
                    }
                    .toListL
                    .start

                  // Send 3 messages, which should result in two incoming channels emitted.
                  // Allow some time between them so the third message doesn't end up in the first channel that gets released.
                  _ <- List.range(0, 3).traverse(i => client12.sendMessage(i.toString).delayExecution(50.millis))

                  // Give the server time to process all messages
                  _ <- Task(messageCounter.await(timeout.toMillis, TimeUnit.MILLISECONDS))
                  // Release so the server topic is closed and we get the results.
                  _ <- pg2Release
                  ports <- listener.join

                } yield {
                  ports shouldBe List(pg1.processAddress, pg1.processAddress)
                }
            }
      }
      .runSyncUnsafe(timeout)
  }

  it should "replicate incoming messages to all client channels connected to the remote address" in {
    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg2 <- initUdpPeerGroup[String]()
      pg3 <- initUdpPeerGroup[String]()
      client21 <- pg2.client(pg1.processAddress)
      client31a <- pg3.client(pg1.processAddress)
      client31b <- pg3.client(pg1.processAddress)
    } yield (pg1, client21, client31a, client31b))
      .use {
        case (pg1, client21, client31a, client31b) =>
          for {
            _ <- runEchoServer(pg1).startAndForget

            _ <- client21.sendMessage("Two to One")
            _ <- client31a.sendMessage("Three to One A")
            _ <- client31b.sendMessage("Three to One B")

            receivers <- List(client21, client31a, client31b).traverse(startCollectingMessages(1.second))
            received <- receivers.traverse(_.join)
          } yield {
            received(0) shouldBe List("Two to One")
            received(1) shouldBe List("Three to One A", "Three to One B")
            received(2) shouldBe received(1)
          }
      }
      .runSyncUnsafe(timeout)
  }

  it should "replicate incoming messages to clients as well as the server channel" in {
    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg2 <- initUdpPeerGroup[String]()
      client21 <- pg2.client(pg1.processAddress)
    } yield (pg1, pg2, client21))
      .use {
        case (pg1, pg2, client21) =>
          for {
            _ <- runEchoServer(pg1).startAndForget

            message = "Requests and responses look the same."
            _ <- client21.sendMessage(message)

            server21 <- pg2.server.refCount.collectChannelCreated.map {
              case (channel, _) => channel
            }.headL

            receivers <- List(client21, server21).traverse(startCollectingMessages(1.second))
            received <- receivers.traverse(_.join)
          } yield {
            received(0) shouldBe List(message)
            received(1) shouldBe received(0)
          }
      }
      .runSyncUnsafe(timeout)
  }
}
