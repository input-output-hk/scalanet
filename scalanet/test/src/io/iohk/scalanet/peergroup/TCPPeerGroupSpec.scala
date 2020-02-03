package io.iohk.scalanet.peergroup

import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.codec.FramingCodec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.PeerGroup.ChannelBrokenException
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import io.iohk.scalanet.peergroup.StandardTestPack.messagingTest
import monix.execution.CancelableFuture
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.RecoverMethods._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import scodec.Codec
import scodec.codecs.implicits.implicitStringCodec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

class TCPPeerGroupSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5 seconds)

  implicit val codec = new FramingCodec(Codec[String])

  behavior of "TCPPeerGroup"

  it should "report an error for messaging to an invalid address" in
    withARandomTCPPeerGroup[String] { alice =>
      StandardTestPack.shouldErrorForMessagingAnInvalidAddress(alice, InetMultiAddress(NetUtils.aRandomAddress()))
    }

  it should "report an error for messaging on a closed channel -- server closes" in
    withTwoRandomTCPPeerGroups[String] { (alice, bob) =>
      val alicesMessage = Random.alphanumeric.take(1024).mkString
      bob.server().connect()
      bob.server().collectChannelCreated.foreachL(channel => channel.close().runToFuture).runToFuture

      val aliceClient = alice.client(bob.processAddress).evaluated
      val aliceError = recoverToExceptionIf[ChannelBrokenException[InetMultiAddress]] {
        aliceClient.sendMessage(alicesMessage).runToFuture
      }

      aliceError.futureValue.to shouldBe bob.processAddress
    }

  it should "report an error for messaging on a closed channel -- client closes" in
    withTwoRandomTCPPeerGroups[String] { (alice, bob) =>
      val bobsMessage = Random.alphanumeric.take(1024).mkString

      bob.server().collectChannelCreated.foreachL(channel => channel.sendMessage(bobsMessage).runToFuture).runToFuture
      val bobChannel: CancelableFuture[Channel[InetMultiAddress, String]] =
        bob.server().collectChannelCreated.headL.runToFuture

      val aliceClient = alice.client(bob.processAddress).evaluated
      bob.server().collectChannelCreated.foreach(_.in.connect())
      bob.server().connect()

      aliceClient.close().evaluated
      val bobError = recoverToExceptionIf[ChannelBrokenException[InetMultiAddress]] {
        bobChannel.futureValue.sendMessage(bobsMessage).runToFuture
      }

      bobError.futureValue.to shouldBe alice.processAddress
    }

  it should "send and receive a message" in
    withTwoRandomTCPPeerGroups[String] { (alice, bob) =>
      messagingTest(alice, bob)
    }

  it should "shutdown a TCPPeerGroup properly" in {
    val tcpPeerGroup = randomTCPPeerGroup[String]
    isListening(tcpPeerGroup.config.bindAddress) shouldBe true

    tcpPeerGroup.shutdown().runToFuture.futureValue

    isListening(tcpPeerGroup.config.bindAddress) shouldBe false
  }

  it should "report the same address for two inbound channels" in
    withTwoRandomTCPPeerGroups[String] { (alice, bob) =>
      StandardTestPack.serverMultiplexingTest(alice, bob)
    }

  it should "throw InitializationError when port already in use" in {
    val address = aRandomAddress()
    val pg1 = new TCPPeerGroup(TCPPeerGroup.Config(address))
    val pg2 = new TCPPeerGroup(TCPPeerGroup.Config(address))
    Await.result(pg1.initialize().runToFuture, 10 seconds)
    assertThrows[InitializationError] {
      Await.result(pg2.initialize().runToFuture, 10 seconds)
    }
    pg1.shutdown().runToFuture.futureValue
  }

}
