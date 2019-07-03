package io.iohk.scalanet.peergroup

import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.scalanet.NetUtils._

import scala.concurrent.duration._
import io.iohk.decco.auto._
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.Codec
import io.iohk.scalanet.NetUtils
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.scalanet.peergroup.PeerGroup.MessageMTUException
import io.iohk.scalanet.peergroup.ScalanetTestSuite.messagingTest
import org.scalatest.RecoverMethods._

class UDPPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "UDPPeerGroup"

  it should "report an error for sending a message greater than the MTU" in
    withARandomUDPPeerGroup[Array[Byte]] { alice =>
      val address = InetMultiAddress(NetUtils.aRandomAddress())
      val invalidMessage = NetUtils.randomBytes(16777216)
      val messageSize = Codec[Array[Byte]].encode(invalidMessage).capacity()

      val error = recoverToExceptionIf[MessageMTUException[InetMultiAddress]] {
        alice.client(address).flatMap(channel => channel.sendMessage(invalidMessage)).runAsync
      }.futureValue

      error.size shouldBe messageSize
    }

  it should "send and receive a message" in withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
    messagingTest(alice, bob)
  }

  it should "shutdown cleanly" in {
    val pg1 = randomUDPPeerGroup[String]
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runAsync.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }
}
