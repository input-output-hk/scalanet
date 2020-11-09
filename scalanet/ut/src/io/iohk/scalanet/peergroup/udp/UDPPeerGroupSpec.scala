package io.iohk.scalanet.peergroup.udp

import cats.implicits._
import cats.effect.Resource
import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.peergroup.implicits._
import io.iohk.scalanet.peergroup.Channel.{DecodingError, MessageReceived}
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelAlreadyClosedException, MessageMTUException}
import io.iohk.scalanet.peergroup.{InetMultiAddress, PeerGroup, Channel}
import io.iohk.scalanet.peergroup.StandardTestPack
import io.iohk.scalanet.peergroup.TestMessage
import java.net.InetSocketAddress
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{EitherValues, FlatSpec}
import org.scalatest.concurrent.Eventually
import org.scalatest.Matchers._
import org.scalatest.RecoverMethods._
import scala.concurrent.duration._
import scodec.bits.ByteVector
import scodec.Codec
import scodec.codecs.implicits._

abstract class UDPPeerGroupSpec[PG <: UDPPeerGroupSpec.TestGroup[_]](name: String)
    extends FlatSpec
    with EitherValues
    with Eventually {
  import UDPPeerGroupSpec.TestGroup
  import scala.language.reflectiveCalls

  val testTimeout = 15.seconds

  implicit val scheduler = Scheduler.fixedPool("test", 16)

  implicit val pc: PatienceConfig = PatienceConfig(5 seconds)

  behavior of name

  def initUdpPeerGroup[M](
      address: InetSocketAddress = aRandomAddress()
  )(implicit s: Scheduler, c: Codec[M]): Resource[Task, TestGroup[M]]

  def randomUDPPeerGroup[M](
      implicit scheduler: Scheduler,
      codec: Codec[M]
  ): Resource[Task, TestGroup[M]] =
    initUdpPeerGroup()

  def withTwoRandomUDPPeerGroups[M](
      testCode: (TestGroup[M], TestGroup[M]) => Task[_]
  )(implicit scheduler: Scheduler, codec: Codec[M]): Unit = {
    (for {
      pg1 <- randomUDPPeerGroup[M]
      pg2 <- randomUDPPeerGroup[M]
    } yield (pg1, pg2))
      .use {
        case (pg1, pg2) =>
          testCode(pg1, pg2).void
      }
      .runSyncUnsafe(testTimeout)
  }

  def withARandomUDPPeerGroup[M](
      testCode: TestGroup[M] => Task[_]
  )(implicit scheduler: Scheduler, codec: Codec[M]): Unit = {
    randomUDPPeerGroup[M]
      .use { pg =>
        testCode(pg).void
      }
      .runSyncUnsafe(testTimeout)
  }

  it should "report an error for sending a message greater than the MTU" in
    withARandomUDPPeerGroup[ByteVector] { alice =>
      val address = InetMultiAddress(NetUtils.aRandomAddress())
      val invalidMessage = ByteVector(NetUtils.randomBytes(16777216))
      val messageSize = Codec[ByteVector].encode(invalidMessage).toOption.get.toByteBuffer.capacity()

      Task.deferFuture {
        recoverToExceptionIf[MessageMTUException[InetMultiAddress]] {
          alice
            .client(address)
            .use { channel =>
              channel.sendMessage(invalidMessage)
            }
            .runToFuture
        }
      } map { error =>
        error.size shouldBe messageSize
      }
    }

  // ETCM-345: This sometimes fails for StaticUDPPeerGroupSpec and DynamicUDPPeerGroupsSpec, even with the timeout and cancel workaround.
  // Since ETCM-199 will remove the ConnectableSubject the test uses we can disable it until that's done, or the error is reproduced locally.
  it should "send and receive a message" in withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
    if (sys.env.get("CI").contains("true")) {
      cancel("ETCM-345: Intermittent timeout on Circle CI.")
    } else {
      StandardTestPack.messagingTest(alice, bob)
    }
  }

  it should "send and receive a message with next* methods" in withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
    StandardTestPack.messagingTestNext(alice, bob)
  }

  it should "report the same address for two inbound channels" in
    withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
      StandardTestPack.serverMultiplexingTest(alice, bob)
    }

  it should "shutdown cleanly" in {
    (for {
      pg1nR <- initUdpPeerGroup[String]().allocated
      (pg1, release) = pg1nR
      isListeningAfterStart <- Task.now(isListeningUDP(pg1.processAddress.inetSocketAddress))
      _ <- release
      isListeningAfterShutdown <- Task.now(isListeningUDP(pg1.processAddress.inetSocketAddress))
    } yield {
      assert(isListeningAfterStart)
      assert(!isListeningAfterShutdown)
    }).runSyncUnsafe()
  }

  it should "throw InitializationError when port already in use" in {
    val address = aRandomAddress()

    initUdpPeerGroup[String](address)
      .use { _ =>
        initUdpPeerGroup[String](address).allocated.attempt.map { result =>
          assert(result.isLeft)
          result.left.get shouldBe a[InitializationError]
        }
      }
      .runSyncUnsafe()
  }

  it should "clean up closed channels" in {
    val messageFromClients = "Hello server"
    val randomNonExistingPeer = InetMultiAddress(aRandomAddress())

    initUdpPeerGroup[String]()
      .use { pg1 =>
        pg1.client(randomNonExistingPeer).use { pg1Channel =>
          pg1Channel.sendMessage(messageFromClients)
        } >>
          Task {
            eventually {
              pg1.channelCount shouldEqual 0
            }
          }
      }
      .runSyncUnsafe()
  }

  it should "echo request from clients received from several channels sequentially" in {
    import UDPPeerGroupSpec._
    val messageFromClients = "Hello server"
    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg2 <- initUdpPeerGroup[String]()
      pg3 <- initUdpPeerGroup[String]()
    } yield (pg1, pg2, pg3))
      .use {
        case (pg1, pg2, pg3) =>
          for {
            _ <- runDiscardServer(pg1).startAndForget
            _ <- runEchoServer(pg2).startAndForget
            _ <- runDiscardServer(pg3).startAndForget
            response <- requestResponse(pg1, pg2.processAddress, messageFromClients)
            response1 <- requestResponse(pg3, pg2.processAddress, messageFromClients)
            response2 <- requestResponse(pg1, pg2.processAddress, messageFromClients)
            response3 <- requestResponse(pg3, pg2.processAddress, messageFromClients)
          } yield {
            response shouldEqual messageFromClients
            response1 shouldEqual messageFromClients
            response2 shouldEqual messageFromClients
            response3 shouldEqual messageFromClients

            eventually {
              assert(pg1.channelCount == 0)
              assert(pg2.channelCount == 0)
              assert(pg3.channelCount == 0)
            }
          }
      }
      .runSyncUnsafe()
  }

  it should "echo request from several clients received from several channels in parallel" in {
    import UDPPeerGroupSpec._
    val messageFromClients = "Hello server"

    List
      .fill(4)(initUdpPeerGroup[String]())
      .sequence
      .use {
        case List(pg1, pg2, pg3, pg4) =>
          for {
            _ <- runDiscardServer(pg1).startAndForget
            _ <- runDiscardServer(pg2).startAndForget
            _ <- runDiscardServer(pg3).startAndForget
            _ <- runEchoServer(pg4).startAndForget
            responses <- Task.parZip3(
              requestResponse(pg1, pg4.processAddress, messageFromClients),
              requestResponse(pg2, pg4.processAddress, messageFromClients),
              requestResponse(pg3, pg4.processAddress, messageFromClients)
            )
            (pg1Response, pg2Response, pg3Response) = responses
            _ <- Task.parZip3(
              requestResponse(pg1, pg4.processAddress, messageFromClients),
              requestResponse(pg2, pg4.processAddress, messageFromClients),
              requestResponse(pg3, pg4.processAddress, messageFromClients)
            )
            responses1 <- Task.parZip3(
              requestResponse(pg1, pg4.processAddress, messageFromClients),
              requestResponse(pg2, pg4.processAddress, messageFromClients),
              requestResponse(pg3, pg4.processAddress, messageFromClients)
            )
            (pg1Response1, pg2Response1, pg3Response1) = responses1
          } yield {
            pg1Response shouldEqual messageFromClients
            pg2Response shouldEqual messageFromClients
            pg3Response shouldEqual messageFromClients
            pg1Response1 shouldEqual messageFromClients
            pg2Response1 shouldEqual messageFromClients
            pg3Response1 shouldEqual messageFromClients

            eventually {
              assert(pg1.channelCount == 0)
              assert(pg2.channelCount == 0)
              assert(pg3.channelCount == 0)
              assert(pg4.channelCount == 0)
            }

          }
      }
      .runSyncUnsafe()
  }

  it should "inform user when trying to send message from already closed channel" in {
    val messageFromClients = "Hello server"
    val randomNonExistingPeer = InetMultiAddress(aRandomAddress())

    initUdpPeerGroup[String]()
      .use { pg1 =>
        pg1.client(randomNonExistingPeer).allocated.flatMap {
          case (pg1Channel, release) =>
            for {
              _ <- pg1Channel.sendMessage(messageFromClients)
              _ <- release
              result <- pg1Channel.sendMessage(messageFromClients).attempt
            } yield {
              result.left.value shouldBe a[ChannelAlreadyClosedException[_]]
            }
        }
      }
      .runSyncUnsafe()
  }

  it should "inform user if there was decoding error on the channel" in {
    import scodec.codecs.implicits._
    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg2 <- initUdpPeerGroup[TestMessage[String]]()
      ch1 <- pg1.client(pg2.processAddress)
    } yield (ch1, pg2))
      .use {
        case (ch1, pg2) =>
          for {
            result <- Task.parZip2(
              ch1.sendMessage("Helllo wrong server"),
              pg2.serverEventObservable.collectChannelCreated.mergeMap {
                case (ch, release) => ch.messageObservable.guarantee(release)
              }.headL
            )
            (_, receivedEvent) = result
          } yield {
            assert(receivedEvent == DecodingError)
          }
      }
      .runSyncUnsafe()
  }
}

object UDPPeerGroupSpec {
  type HasChannels = {
    def channelCount: Int
  }
  type TestGroup[M] = PeerGroup[InetMultiAddress, M] with HasChannels

  def requestResponse[M](
      peerGroup: PeerGroup[InetMultiAddress, M],
      to: InetMultiAddress,
      message: M,
      requestTimeout: FiniteDuration = 2.seconds
  ): Task[M] = {
    peerGroup
      .client(to)
      .use { clientChannel =>
        sendRequest(message, clientChannel, requestTimeout)
      }
  }

  def sendRequest[M](
      message: M,
      clientChannel: Channel[InetMultiAddress, M],
      requestTimeout: FiniteDuration
  ): Task[M] = {
    for {
      _ <- clientChannel.sendMessage(message).timeout(requestTimeout)
      response <- clientChannel.messageObservable
        .collect { case MessageReceived(m) => m }
        .headL
        .timeout(requestTimeout)
    } yield response
  }

  // echo server which closes every incoming channel after response
  def runEchoServer[M](peerGroup: PeerGroup[InetMultiAddress, M], doRelease: Boolean = true)(
      implicit s: Scheduler
  ): Task[Unit] = {
    peerGroup.serverEventObservable.collectChannelCreated
      .mergeMap {
        case (channel, release) =>
          channel.messageObservable
            .collect {
              case MessageReceived(m) => m
            }
            .map(request => (channel, release, request))
      }
      .foreachL {
        case (channel, release, msg) =>
          channel.sendMessage(msg).guarantee(release.whenA(doRelease)).runAsyncAndForget
      }
  }

  // A server which dicards incoming messages and closes the channel.
  // This is necessary for the StaticUDPPeerGroup because it creates a server channel
  // for responses, which would stay open unless consumed.
  def runDiscardServer[M](peerGroup: PeerGroup[InetMultiAddress, M])(implicit s: Scheduler): Task[Unit] = {
    peerGroup.serverEventObservable.collectChannelCreated
      .mapEval {
        case (_, release) => release
      }
      .foreachL(_ => ())
  }
}
