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
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.RecoverMethods._
import org.scalatest.concurrent.Eventually
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs.implicits._

class UDPPeerGroupSpec extends FlatSpec with EitherValues with Eventually {
  implicit val scheduler = Scheduler.fixedPool("test", 16)

  implicit val pc: PatienceConfig = PatienceConfig(5 seconds)

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
    import UDPPeerGroupSpecUtils._

    (for {
      pg1 <- initUdpPeerGroup[String]()
      isListeningAfterStart <- Task.now(isListeningUDP(pg1.config.bindAddress))
      _ <- pg1.shutdown()
      isListeningAfterShutdown <- Task.now(isListeningUDP(pg1.config.bindAddress))
    } yield {
      assert(isListeningAfterStart)
      assert(!isListeningAfterShutdown)
    }).runSyncUnsafe()
  }

  it should "throw InitializationError when port already in use" in {
    import UDPPeerGroupSpecUtils._
    val address = aRandomAddress()

    (for {
      pg1 <- initUdpPeerGroup[String](address)
      pg2Result <- initUdpPeerGroup[String](address).attempt
    } yield {
      assert(pg2Result.isLeft)
      pg2Result.left.get shouldBe a[InitializationError]
    }).runSyncUnsafe()
  }

  it should "clean up closed channels" in {
    import UDPPeerGroupSpecUtils._
    val messageFromClients = "Hello server"
    val randomNonExistingPeer = InetMultiAddress(aRandomAddress())
    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg1Channel <- pg1.client(randomNonExistingPeer)
      _ <- pg1Channel.sendMessage(messageFromClients)
      _ <- pg1Channel.close()
    } yield {
      eventually {
        pg1.activeChannels.size() shouldEqual 0
      }
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
      response2 <- requestResponse(pg1, pg2.processAddress, messageFromClients)
      response3 <- requestResponse(pg3, pg2.processAddress, messageFromClients)
    } yield {
      response shouldEqual messageFromClients
      response1 shouldEqual messageFromClients
      response2 shouldEqual messageFromClients
      response3 shouldEqual messageFromClients
      eventually {
        assert(pg1.activeChannels.isEmpty)
        assert(pg2.activeChannels.isEmpty)
        assert(pg3.activeChannels.isEmpty)
      }
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
        assert(pg1.activeChannels.isEmpty)
        assert(pg2.activeChannels.isEmpty)
        assert(pg3.activeChannels.isEmpty)
        assert(pg4.activeChannels.isEmpty)
      }

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
    import scodec.codecs.implicits._
    (for {
      pg1 <- initUdpPeerGroup[String]()
      pg2 <- initUdpPeerGroup[TestMessage[String]]()
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
    val pg = new UDPPeerGroup[M](UDPPeerGroup.Config(address))
    pg.initialize().map(_ => pg)
  }

}
