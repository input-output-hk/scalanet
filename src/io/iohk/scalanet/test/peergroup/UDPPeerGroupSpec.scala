package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.NetUtils.aRandomAddress
import io.iohk.scalanet.peergroup.UDPPeerGroup.Config
import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.Matchers._
import io.iohk.scalanet.peergroup.future._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class UDPPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(1 second)

  behavior of "UDPPeerGroup"

  it should "send and receive a message" in withTwoRandomUDPPeerGroups { (pg1, pg2) =>
    val value: Future[ByteBuffer] = pg2.messageStream.head()
    val b: Array[Byte] = "Hello".getBytes(UTF_8)

    pg1.sendMessage(pg2.udpPeerGroupConfig.bindAddress, ByteBuffer.wrap(b))

    NetUtils.toArray(value.futureValue) shouldBe b
  }

  it should "shutdown cleanly" in {
    val pg1 = randomUDPPeerGroup()
    NetUtils.isListeningUDP(pg1.udpPeerGroupConfig.bindAddress) shouldBe true

    pg1.shutdown().futureValue

    NetUtils.isListeningUDP(pg1.udpPeerGroupConfig.bindAddress) shouldBe false
  }

  private def randomUDPPeerGroup() = new UDPPeerGroup(Config(aRandomAddress()))

  private def withTwoRandomUDPPeerGroups(testCode: (UDPPeerGroup[Future], UDPPeerGroup[Future]) => Any): Unit = {
    val pg1 = randomUDPPeerGroup()
    val pg2 = randomUDPPeerGroup()
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }
}
