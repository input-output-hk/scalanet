package io.iohk.scalanet.peergroup

import java.net.BindException

import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.peergroup.TCPPeerGroup.Config
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import org.scalatest.EitherValues._
import io.iohk.decco.auto._

import scala.concurrent.duration._
import scala.util.Random

class TCPPeerGroupSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "TCPPeerGroup"

  it should "send a message to a TCPPeerGroup" in
    withTwoRandomTCPPeerGroups { (alice, bob) =>
      val message: String = Random.nextString(1024 * 1024 * 10)
      val bobsChannel = bob.messageChannel[String]
      val messageReceivedF = bobsChannel.headL.runToFuture

      alice.sendMessage(bob.config.bindAddress, message).runToFuture
      val messageReceived = messageReceivedF.futureValue

      messageReceived shouldBe (alice.processAddress, message)
    }

  it should "shutdown a TCPPeerGroup properly" in {
    val tcpPeerGroup = randomTCPPeerGroup
    isListening(tcpPeerGroup.config.bindAddress) shouldBe true

    tcpPeerGroup.shutdown().runToFuture.futureValue

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
