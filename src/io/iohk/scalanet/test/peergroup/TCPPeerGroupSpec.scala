package io.iohk.scalanet.peergroup

import io.iohk.scalanet.NetUtils._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import io.iohk.decco.auto._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

class TCPPeerGroupSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "TCPPeerGroup"

  it should "send a message to a TCPPeerGroup" in
    withTwoRandomTCPPeerGroups { (alice, bob) =>
      val message: String = Random.nextString(1024 * 1024 * 10)

      val bobReceivedF: Future[String] = bob.server().flatMap(channel => channel.in).headL.runToFuture

      alice.client(bob.processAddress).sendMessage(message).runToFuture
      val bobReceived = bobReceivedF.futureValue

      bobReceived shouldBe message
    }

  it should "shutdown a TCPPeerGroup properly" in {
    val tcpPeerGroup = randomTCPPeerGroup
    isListening(tcpPeerGroup.config.bindAddress) shouldBe true

    tcpPeerGroup.shutdown().runToFuture.futureValue

    isListening(tcpPeerGroup.config.bindAddress) shouldBe false
  }
}
