package io.iohk.scalanet.test

import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.ByteBuffer

import io.iohk.scalanet.peergroup.{TCPPeerGroup, TCPPeerGroupConfig}
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import io.iohk.scalanet.peergroup.future._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.Future
import scala.concurrent.duration._
import org.scalatest.Matchers._

class TCPPeerGroupSpec extends FlatSpec with BeforeAndAfterAll {

  implicit val patienceConfig = PatienceConfig(1 second)

  behavior of "TCPPeerGroup"

  it should "send a message to a TCPPeerGroup" in
  networks(randomNetwork(), randomNetwork()) { networks =>

    val alice = networks(0)
    val bob = networks(1)
    val message = bob.tcpPeerGroup.messageStream.head()
    val messageFromAlice = ByteBuffer.wrap("Hi Bob!!!".getBytes)

    alice.tcpPeerGroup.sendMessage(bob.tcpAddress, messageFromAlice).futureValue

    toArray(message.futureValue) shouldBe "Hi Bob!!!".getBytes

  }
  it should "shutdown a TCPPeerGroup properly" in {

    val netWork = randomNetwork()
    isListening(netWork.tcpAddress) shouldBe true
    netWork.tcpPeerGroup.shutdown().futureValue
    isListening(netWork.tcpAddress) shouldBe false

  }


  private case class NetworkFixture(tcpPeerGroup: TCPPeerGroup[Future],tcpAddress:InetSocketAddress,tcpPeerGroupConfig:TCPPeerGroupConfig)

  private def randomNetwork(): NetworkFixture = {
    val tcpAddress: InetSocketAddress = aRandomAddress()
    val tcpPeerGroupConfig = TCPPeerGroupConfig(tcpAddress)

    val tcpPeerGroup =
      new TCPPeerGroup(tcpPeerGroupConfig)

    NetworkFixture(tcpPeerGroup ,tcpAddress,tcpPeerGroupConfig)
  }

  private def networks(fixtures: NetworkFixture*)(testCode: Seq[NetworkFixture] => Any): Unit = {
    try {
      testCode(fixtures)
    } finally {
      fixtures.foreach(fixture => fixture.tcpPeerGroup.shutdown())
    }
  }

  def aRandomAddress(): InetSocketAddress = {
    val s = new ServerSocket(0)
    try {
      new InetSocketAddress("localhost", s.getLocalPort)
    } finally {
      s.close()
    }
  }

  def toArray(b: ByteBuffer): Array[Byte] = {
    val a = new Array[Byte](b.remaining())
    b.get(a)
    a
  }



  def isListening(address: InetSocketAddress): Boolean = {
    try {
      new Socket(address.getHostName, address.getPort).close()
      true
    } catch {
      case e: Exception =>
        false
    }
  }
}