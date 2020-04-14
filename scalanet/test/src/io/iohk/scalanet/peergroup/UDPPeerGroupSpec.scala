package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress

import org.scalatest.{EitherValues, FlatSpec}
import org.scalatest.Matchers._
import io.iohk.scalanet.NetUtils._

import scala.concurrent.duration._
import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.peergroup.Channel.{DecodingError, MessageReceived}
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelAlreadyClosedException, MessageMTUException}
import io.iohk.scalanet.peergroup.StandardTestPack._
import io.iohk.scalanet.peergroup.kademlia.KMessage
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.TestScheduler
import org.scalatest.RecoverMethods._
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs.implicits._

import scala.concurrent.Await

class UDPPeerGroupSpec extends FlatSpec with EitherValues {
  implicit val scheduler = Scheduler.fixedPool("test", 16)

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "UDPPeerGroup"

  it should "report an error for sending a message greater than the MTU" in
    withARandomUDPPeerGroup[ByteVector] { alice =>
      val address = InetMultiAddress(NetUtils.aRandomAddress())
      val invalidMessage = ByteVector(NetUtils.randomBytes(16777216))
      val messageSize = Codec[ByteVector].encode(invalidMessage).toOption.get.toByteBuffer.capacity()

      val error = recoverToExceptionIf[MessageMTUException[InetMultiAddress]] {
        alice.client(address).flatMap(channel => channel.sendMessage(invalidMessage)).runToFuture
      }.futureValue

      error.size shouldBe messageSize
    }

  it should "send and receive a message" in withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
    messagingTest(alice, bob)
  }

  it should "report the same address for two inbound channels" in
    withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
      StandardTestPack.serverMultiplexingTest(alice, bob)
    }

  it should "shutdown cleanly" in {
    val pg1 = randomUDPPeerGroup[String]
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runToFuture.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }

  it should "throw InitializationError when port already in use" in {
    val address = aRandomAddress()
    val pg1 = new UDPPeerGroup[String](UDPPeerGroup.Config(address))
    val pg2 = new UDPPeerGroup[String](UDPPeerGroup.Config(address))

    Await.result(pg1.initialize().runToFuture, 10 seconds)
    assertThrows[InitializationError] {
      Await.result(pg2.initialize().runToFuture, 10 seconds)
    }
    pg1.shutdown().runToFuture.futureValue
  }

  it should "clean up closed channels in background" in {
    import UDPPeerGroupSpecUtils._
    val messageFromClients = "Hello server"
    val randomNonExistingPeer = InetMultiAddress(aRandomAddress())
    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg1Channel <- pg1.client(randomNonExistingPeer)
      _ <- pg1Channel.sendMessage(messageFromClients)
      _ <- pg1Channel.close()
      _ = testScheduler.tick(pg1.config.cleanUpInitialDelay)
    } yield {
      pg1.activeChannels.size() shouldEqual 0
    }).runSyncUnsafe()
  }

  it should "echo request from clients received from several channels sequentially" in {
    import UDPPeerGroupSpecUtils._
    val messageFromClients = "Hello server"
    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg2 <- initUdpPeerGroup[String]()
      pg3 <- initUdpPeerGroup[String]()
      _ <- echoServer(pg2).startAndForget
      response <- requestResponse(pg1, pg2.processAddress, messageFromClients)
      response1 <- requestResponse(pg3, pg2.processAddress, messageFromClients)
      numOfServerChannelsAfter2Requests = pg2.activeChannels.size()
      response2 <- requestResponse(pg1, pg2.processAddress, messageFromClients)
      response3 <- requestResponse(pg3, pg2.processAddress, messageFromClients)
      numOfServerChannelsAfter4Requests = pg2.activeChannels.size()
      _ = testScheduler.tick(pg2.config.cleanUpInitialDelay)
      numOfServerChannelsAfterCleanup = pg2.activeChannels.size()
      numOfClient1ChannelsAfterCleanup = pg1.activeChannels.size()
      numOfClient2ChannelsAfterCleanup = pg3.activeChannels.size()
    } yield {
      response shouldEqual messageFromClients
      response1 shouldEqual messageFromClients
      response2 shouldEqual messageFromClients
      response3 shouldEqual messageFromClients
      numOfServerChannelsAfter2Requests shouldEqual 2
      numOfServerChannelsAfter4Requests shouldEqual 4
      numOfServerChannelsAfterCleanup shouldEqual 0
      numOfClient1ChannelsAfterCleanup shouldEqual 0
      numOfClient2ChannelsAfterCleanup shouldEqual 0
    }).runSyncUnsafe()
  }

  it should "echo request from several clients received from several channels in parallel" in {
    import UDPPeerGroupSpecUtils._
    val messageFromClients = "Hello server"

    (for {
      result <- Task.parZip4(
        initUdpPeerGroup[String](),
        initUdpPeerGroup[String](),
        initUdpPeerGroup[String](),
        initUdpPeerGroup[String]()
      )
      (pg1, pg2, pg3, pg4) = result
      _ <- echoServer(pg4).startAndForget
      responses <- Task.parZip3(
        requestResponse(pg1, pg4.processAddress, messageFromClients),
        requestResponse(pg2, pg4.processAddress, messageFromClients),
        requestResponse(pg3, pg4.processAddress, messageFromClients)
      )
      numOfServerChannelsAfter1Round = pg4.activeChannels.size()
      (pg1Response, pg2Response, pg3Response) = responses
      _ <- Task.parZip3(
        requestResponse(pg1, pg4.processAddress, messageFromClients),
        requestResponse(pg2, pg4.processAddress, messageFromClients),
        requestResponse(pg3, pg4.processAddress, messageFromClients)
      )
      numOfServerChannelsAfter2Round = pg4.activeChannels.size()
      _ = testScheduler.tick(pg4.config.cleanUpInitialDelay)
      numOfServerChannelsAfterCleanUp = pg4.activeChannels.size()
      responses1 <- Task.parZip3(
        requestResponse(pg1, pg4.processAddress, messageFromClients),
        requestResponse(pg2, pg4.processAddress, messageFromClients),
        requestResponse(pg3, pg4.processAddress, messageFromClients)
      )
      (pg1Response1, pg2Response1, pg3Response1) = responses1
      numOfServerChannelsAfter3Round = pg4.activeChannels.size()
      _ = testScheduler.tick(pg4.config.cleanUpPeriod)
      numOfServerChannelsAfter2CleanUp = pg4.activeChannels.size()

    } yield {
      pg1Response shouldEqual messageFromClients
      pg2Response shouldEqual messageFromClients
      pg3Response shouldEqual messageFromClients
      numOfServerChannelsAfter1Round shouldEqual 3
      numOfServerChannelsAfter2Round shouldEqual 6
      numOfServerChannelsAfterCleanUp shouldEqual 0
      pg1Response1 shouldEqual messageFromClients
      pg2Response1 shouldEqual messageFromClients
      pg3Response1 shouldEqual messageFromClients
      numOfServerChannelsAfter3Round shouldEqual 3
      numOfServerChannelsAfter2CleanUp shouldEqual 0
    }).runSyncUnsafe()
  }

  it should "inform user when trying to send message from already closed channel" in {
    import UDPPeerGroupSpecUtils._
    val messageFromClients = "Hello server"
    val randomNonExistingPeer = InetMultiAddress(aRandomAddress())

    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg1Channel <- pg1.client(randomNonExistingPeer)
      _ <- pg1Channel.sendMessage(messageFromClients)
      _ <- pg1Channel.close()
      result <- pg1Channel.sendMessage(messageFromClients).attempt
    } yield {
      result.left.value shouldBe a[ChannelAlreadyClosedException[_]]
    }).runSyncUnsafe()
  }

  it should "inform user if there was decoding error on the channel" in {
    import UDPPeerGroupSpecUtils._
    import io.iohk.scalanet.codec.DefaultCodecs.KademliaMessages._
    import io.iohk.scalanet.codec.DefaultCodecs.General._
    import scodec.codecs.implicits._
    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg2 <- initUdpPeerGroup[KMessage[String]]()
      ch1 <- pg1.client(pg2.processAddress)
      result <- Task.parZip2(
        ch1.sendMessage("Helllo wrong server"),
        pg2.server().refCount.collectChannelCreated.mergeMap(_.in.refCount).headL
      )
      (_, receivedEvent) = result
    } yield {
      assert(receivedEvent == DecodingError)
    }).runSyncUnsafe()
  }
}

object UDPPeerGroupSpecUtils {
  val testScheduler = TestScheduler()
  def requestResponse[M](
      peerGroup: PeerGroup[InetMultiAddress, M],
      to: InetMultiAddress,
      message: M,
      requestTimeout: FiniteDuration = 2.seconds
  ): Task[M] = {
    peerGroup
      .client(to)
      .bracket { clientChannel =>
        sendRequest(message, clientChannel, requestTimeout)
      } { clientChannel =>
        clientChannel.close()
      }
  }

  def sendRequest[M](
      message: M,
      clientChannel: Channel[InetMultiAddress, M],
      requestTimeout: FiniteDuration
  ): Task[M] = {
    for {
      _ <- clientChannel.sendMessage(message).timeout(requestTimeout)
      response <- clientChannel.in.refCount
        .collect { case MessageReceived(m) => m }
        .headL
        .timeout(requestTimeout)
    } yield response
  }

  def echoServer[M](peerGroup: PeerGroup[InetMultiAddress, M])(implicit s: Scheduler) = {
    // echo server which closes every incoming channel after response
    peerGroup
      .server()
      .refCount
      .collectChannelCreated
      .mergeMap { channel =>
        channel.in.refCount.collect { case MessageReceived(m) => m }.map(request => (channel, request))
      }
      .foreachL {
        case (channel, msg) =>
          Task
            .eval(channel)
            .bracket(ch => ch.sendMessage(msg)) { ch =>
              ch.close()
            }
            .runAsyncAndForget
      }
  }

  def initUdpPeerGroup[M](
      address: InetSocketAddress = aRandomAddress()
  )(implicit s: Scheduler, c: Codec[M]): Task[UDPPeerGroup[M]] = {
    val pg = new UDPPeerGroup[M](UDPPeerGroup.Config(address), cleanupScheduler = testScheduler)
    pg.initialize().map(_ => pg)
  }

}
