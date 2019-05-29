package io.iohk.scalanet

import java.net._
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.Certificate

import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.scalanet.peergroup.{InetMultiAddress, PeerGroup, TCPPeerGroup, UDPPeerGroup}
import monix.execution.Scheduler

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random
import scala.collection.JavaConverters._

object NetUtils {

  val keyStore: KeyStore = loadKeyStore("keystore.p12", "password")
  val trustStore: KeyStore = loadKeyStore("truststore.p12", "password")
  val trustedCerts: List[Certificate] =
    trustStore.aliases().asScala.toList.map(alias => trustStore.getCertificate(alias))

  def loadKeyStore(keystoreLocation: String, keystorePassword: String): KeyStore = {
    val keystore = KeyStore.getInstance("PKCS12")
    keystore.load(NetUtils.getClass.getClassLoader.getResourceAsStream(keystoreLocation), keystorePassword.toCharArray)
    keystore
  }

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

  def randomTerminalPeerGroup[M](
      t: SimpleTerminalPeerGroup
  )(
      implicit scheduler: Scheduler,
      codec: Codec[M],
      bufferInstantiator: BufferInstantiator[ByteBuffer]
  ): PeerGroup[InetMultiAddress, M] =
    t match {
      case TcpTerminalPeerGroup => randomTCPPeerGroup
      case UdpTerminalPeerGroup => randomUDPPeerGroup
    }

  def randomTCPPeerGroup[M](
      implicit scheduler: Scheduler,
      codec: Codec[M],
      bufferInstantiator: BufferInstantiator[ByteBuffer]
  ): TCPPeerGroup[M] = {
    val pg = new TCPPeerGroup(TCPPeerGroup.Config(aRandomAddress()))
    Await.result(pg.initialize().runAsync, 10 seconds)
    pg
  }

  def randomUDPPeerGroup[M](
      implicit scheduler: Scheduler,
      codec: Codec[M],
      bufferInstantiator: BufferInstantiator[ByteBuffer]
  ): UDPPeerGroup[M] = {
    val pg = new UDPPeerGroup(UDPPeerGroup.Config(aRandomAddress()))
    Await.result(pg.initialize().runAsync, 10 seconds)
    pg
  }

  def withTwoRandomTCPPeerGroups[M](
      testCode: (TCPPeerGroup[M], TCPPeerGroup[M]) => Any
  )(implicit scheduler: Scheduler, codec: Codec[M], bufferInstantiator: BufferInstantiator[ByteBuffer]): Unit = {
    val (pg1, pg2) = random2TCPPeerGroup(scheduler, codec, bufferInstantiator)
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }

  def random2TCPPeerGroup[M](
      implicit scheduler: Scheduler,
      codec: Codec[M],
      bufferInstantiator: BufferInstantiator[ByteBuffer]
  ): (TCPPeerGroup[M], TCPPeerGroup[M]) = {
    val address = aRandomAddress()
    val address2 = aRandomAddress()

    val pg1 = new TCPPeerGroup(TCPPeerGroup.Config(address))
    val pg2 = new TCPPeerGroup(TCPPeerGroup.Config(address2))

    Await.result(pg1.initialize().runAsync, 10 seconds)
    Await.result(pg2.initialize().runAsync, 10 seconds)

    (pg1, pg2)
  }

  def withTwoRandomUDPPeerGroups[M](
      testCode: (UDPPeerGroup[M], UDPPeerGroup[M]) => Any
  )(implicit scheduler: Scheduler, codec: Codec[M], bufferInstantiator: BufferInstantiator[ByteBuffer]): Unit = {
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
