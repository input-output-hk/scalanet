package io.iohk.scalanet

import java.io.OutputStream
import java.net.{DatagramSocket, InetSocketAddress, ServerSocket, Socket}
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

import scala.collection.mutable
import scala.util.Random

object NetUtils {

  def writeUdp(address: InetSocketAddress, data: Array[Byte]): Unit = {
    val udp = DatagramChannel.open()
    udp.configureBlocking(true)
    udp.connect(address)
    try {
      udp.write(ByteBuffer.wrap(data))
    } finally {
      udp.close()
    }
  }

  def writeTo(address: InetSocketAddress, bytes: Array[Byte]): Unit = {
    val socket = new Socket(address.getHostName, address.getPort)
    val out: OutputStream = socket.getOutputStream
    try {
      out.write(bytes)
    } finally {
      out.close()
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

  def discardMessages[Addr, T](remoteAddress: Addr, message: T): Unit = ()

  def logMessages[T](messages: mutable.ListBuffer[T])(remoteAddress: InetSocketAddress, message: T): Unit =
    messages += message

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

  def randomBytes(n: Int): Array[Byte] = {
    val a = new Array[Byte](n)
    Random.nextBytes(a)
    a
  }

  def concatenate(buffs: Seq[ByteBuffer]): ByteBuffer = {
    val allocSize = buffs.foldLeft(0)((acc, nextBuff) => acc + nextBuff.capacity())

    val b0 = ByteBuffer.allocate(allocSize)

    (buffs.foldLeft(b0)((accBuff, nextBuff) => accBuff.put(nextBuff)): java.nio.Buffer).flip().asInstanceOf[ByteBuffer]
  }

  def forwardPort(srcPort: Int, dst: InetSocketAddress): PortForward =
    new PortForward(srcPort, dst)

}
