package io.iohk.scalanet.peergroup

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.Codec
import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.codec.FramingCodec
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
      bob.server().collectChannelCreated.foreachL(channel => channel.close().runAsync).runAsync

      val aliceClient = alice.client(bob.processAddress).evaluated
      val aliceError = recoverToExceptionIf[ChannelBrokenException[InetMultiAddress]] {
        aliceClient.sendMessage(alicesMessage).runAsync
      }

      aliceError.futureValue.to shouldBe bob.processAddress
    }

  it should "report an error for messaging on a closed channel -- client closes" in
    withTwoRandomTCPPeerGroups[String] { (alice, bob) =>
      val bobsMessage = Random.alphanumeric.take(1024).mkString
      bob.server().collectChannelCreated.foreachL(channel => channel.sendMessage(bobsMessage).runAsync).runAsync
      val bobChannel: CancelableFuture[Channel[InetMultiAddress, String]] =
        bob.server().collectChannelCreated.headL.runAsync

      val aliceClient = alice.client(bob.processAddress).evaluated
      aliceClient.close().evaluated
      val bobError = recoverToExceptionIf[ChannelBrokenException[InetMultiAddress]] {
        bobChannel.futureValue.sendMessage(bobsMessage).runAsync
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

    tcpPeerGroup.shutdown().runAsync.futureValue

    isListening(tcpPeerGroup.config.bindAddress) shouldBe false
  }

  it should "report the same address for two inbound channels" in
    withTwoRandomTCPPeerGroups[String] { (alice, bob) =>
      StandardTestPack.serverMultiplexingTest(alice, bob)
    }
}
