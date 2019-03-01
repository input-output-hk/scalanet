package io.iohk.scalanet

import java.net._
import java.nio.ByteBuffer

import io.iohk.scalanet.peergroup.{TCPPeerGroup, UDPPeerGroup}
import monix.execution.Scheduler

import scala.util.Random

object NetUtils {

  def aRandomAddress(): InetSocketAddress = {
    val s = new ServerSocket(0)
    try {
      new InetSocketAddress("localhost", s.getLocalPort)
    } finally {
      s.close()
    }
  }

  def isListening(address: InetSocketAddress): Boolean = {
    try {
      new Socket(address.getHostName, address.getPort).close()
      true
    } catch {
      case _: Exception =>
        false
    }
  }

  def isListeningUDP(address: InetSocketAddress): Boolean = {
    try {
      new DatagramSocket(address).close()
      false
    } catch {
      case _: Exception =>
        true
    }
  }

  def toArray(b: ByteBuffer): Array[Byte] = {
    val a = new Array[Byte](b.remaining())
    b.get(a)
    a
  }

  def withAddressInUse(testCode: InetSocketAddress => Any): Unit = {
    val address = aRandomAddress()
    val socket = new ServerSocket(address.getPort, 0, InetAddress.getLoopbackAddress)
    try {
      testCode(address)
    } finally {
      socket.close()
    }
  }

  def withUDPAddressInUse(testCode: InetSocketAddress => Any): Unit = {
    val socket = new DatagramSocket()
    val address = socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]
    try {
      testCode(address)
    } finally {
      socket.close()
    }
  }

  def randomBytes(n: Int): Array[Byte] = {
    val a = new Array[Byte](n)
    Random.nextBytes(a)
    a
  }
  sealed trait SimpleTerminalPeerGroup
  case object TcpTerminalPeerGroup extends SimpleTerminalPeerGroup
  case object UdpTerminalPeerGroup extends SimpleTerminalPeerGroup

  def randomTerminalPeerGroup(t: SimpleTerminalPeerGroup)(implicit  scheduler: Scheduler) =
    t match {
      case TcpTerminalPeerGroup => randomTCPPeerGroup
      case UdpTerminalPeerGroup => randomUDPPeerGroup
    }
  def randomTCPPeerGroup(implicit  scheduler: Scheduler): TCPPeerGroup =
    new TCPPeerGroup(TCPPeerGroup.Config(aRandomAddress()))

  def withTwoRandomTCPPeerGroups(
      testCode: (TCPPeerGroup, TCPPeerGroup) => Any
  )(implicit scheduler: Scheduler): Unit = {
    val pg1 = randomTCPPeerGroup( scheduler)
    val pg2 = randomTCPPeerGroup( scheduler)
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }

  def randomUDPPeerGroup(implicit scheduler: Scheduler): UDPPeerGroup =
    new UDPPeerGroup(UDPPeerGroup.Config(aRandomAddress()))

  def withTwoRandomUDPPeerGroups(
      testCode: (UDPPeerGroup, UDPPeerGroup) => Any
  )(implicit  scheduler: Scheduler): Unit = {
    val pg1 = randomUDPPeerGroup
    val pg2 = randomUDPPeerGroup
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }

}
