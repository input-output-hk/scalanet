package io.iohk.scalanet.peergroup

import java.net.BindException
import java.nio.ByteBuffer

import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.peergroup.TCPPeerGroup.Config
import io.iohk.scalanet.peergroup.future._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import org.scalatest.EitherValues._

import scala.concurrent.duration._

class TCPPeerGroupSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig = PatienceConfig(1 second)

  behavior of "TCPPeerGroup"

  it should "send a message to a TCPPeerGroup" in
    withTwoRandomTCPPeerGroups { (alice, bob) =>
      val message = randomBytes(1024 * 1024 * 10)
      val messageReceivedF = bob.messageStream.head()

      alice.sendMessage(bob.config.bindAddress, ByteBuffer.wrap(message))
      val messageReceived = messageReceivedF.futureValue

      toArray(messageReceived) shouldBe message
    }

  it should "shutdown a TCPPeerGroup properly" in {
    val tcpPeerGroup = randomTCPPeerGroup
    isListening(tcpPeerGroup.config.bindAddress) shouldBe true

    tcpPeerGroup.shutdown().futureValue

    isListening(tcpPeerGroup.config.bindAddress) shouldBe false
  }

  it should "support a throws create method" in withAddressInUse { address =>
    isListening(address) shouldBe true
    val exception = the[IllegalStateException] thrownBy TCPPeerGroup.createOrThrow(Config(address))
    exception.getCause shouldBe a[BindException]
  }

  it should "support an Either create method" in withAddressInUse { address =>
    isListening(address) shouldBe true
    TCPPeerGroup.create(Config(address)).left.value.cause shouldBe a[BindException]
  }
}
