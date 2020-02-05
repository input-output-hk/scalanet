package io.iohk.scalanet

import java.net._
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.Certificate

import io.iohk.scalanet.codec.StreamCodec
import io.iohk.scalanet.peergroup._
import io.netty.handler.ssl.util.SelfSignedCertificate
import monix.execution.Scheduler
import scodec.Codec

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

object NetUtils {

  val keyStore: KeyStore = loadKeyStore("keystore.p12", "password")
  val trustStore: KeyStore = loadKeyStore("truststore.p12", "password")
  val trustedCerts: Array[Certificate] =
    trustStore.aliases().asScala.toArray.map(alias => trustStore.getCertificate(alias))

  def loadKeyStore(keystoreLocation: String, keystorePassword: String): KeyStore = {
    val keystore = KeyStore.getInstance("PKCS12")
    keystore.load(NetUtils.getClass.getClassLoader.getResourceAsStream(keystoreLocation), keystorePassword.toCharArray)
    keystore
  }

  def aRandomAddress(): InetSocketAddress = InetPeerGroupUtils.aRandomAddress()

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

  def randomTCPPeerGroup[M](
      implicit scheduler: Scheduler,
      codec: StreamCodec[M],
  ): TCPPeerGroup[M] = {
    val pg = new TCPPeerGroup(TCPPeerGroup.Config(aRandomAddress()))
    Await.result(pg.initialize().runToFuture, 10 seconds)
    pg
  }
  def randomTLSPeerGroup[M](
      implicit scheduler: Scheduler,
      codec: StreamCodec[M],
  ): TLSPeerGroup[M] = {
    val sc1 = new SelfSignedCertificate()
    val pg = new TLSPeerGroup(TLSPeerGroup.Config(aRandomAddress(), sc1.key(), List(sc1.cert()), Nil))
    Await.result(pg.initialize().runToFuture, 10 seconds)
    pg
  }

  def randomUDPPeerGroup[M](
      implicit scheduler: Scheduler,
      codec: Codec[M],
  ): UDPPeerGroup[M] = {
    val pg = new UDPPeerGroup(UDPPeerGroup.Config(aRandomAddress()))
    Await.result(pg.initialize().runToFuture, 10 seconds)
    pg
  }

  def withARandomTCPPeerGroup[M](
      testCode: TCPPeerGroup[M] => Any
  )(implicit scheduler: Scheduler, codec: StreamCodec[M]): Unit = {
    val pg = randomTCPPeerGroup(scheduler, codec)
    try {
      testCode(pg)
    } finally {
      pg.shutdown()
    }
  }

  def withTwoRandomTCPPeerGroups[M](
      testCode: (TCPPeerGroup[M], TCPPeerGroup[M]) => Any
  )(implicit scheduler: Scheduler, codec: StreamCodec[M]): Unit = {
    val (pg1, pg2) = random2TCPPeerGroup(scheduler, codec)
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }

  def with3RandomTCPPeerGroups[M](
      testCode: (TCPPeerGroup[M], TCPPeerGroup[M], TCPPeerGroup[M]) => Any
  )(implicit scheduler: Scheduler, codec: StreamCodec[M]): Unit = {
    val (pg1, pg2, pg3) = random3TCPPeerGroup(scheduler, codec)
    try {
      testCode(pg1, pg2, pg3)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
      pg3.shutdown()
    }
  }

  def withTwoRandomTLSPeerGroups[M](clientAuth: Boolean = false)(
      testCode: (TLSPeerGroup[M], TLSPeerGroup[M]) => Any
  )(implicit scheduler: Scheduler, codec: StreamCodec[M]): Unit = {
    val (pg1, pg2) = random2TLSPPeerGroup(clientAuth)(scheduler, codec)
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }

  def random2TLSPPeerGroup[M](
      clientAuth: Boolean
  )(
      implicit scheduler: Scheduler,
      codec: StreamCodec[M],
  ): (TLSPeerGroup[M], TLSPeerGroup[M]) = {
    val address1 = aRandomAddress()
    val address2 = aRandomAddress()
    val sc1 = new SelfSignedCertificate()
    val sc2 = new SelfSignedCertificate()

    val pg1 = new TLSPeerGroup(TLSPeerGroup.Config(address1, sc1.key(), List(sc1.cert()), List(sc2.cert()), clientAuth))
    val pg2 = new TLSPeerGroup(TLSPeerGroup.Config(address2, sc2.key(), List(sc2.cert()), List(sc1.cert()), clientAuth))

    Await.result(pg1.initialize().runToFuture, 10 seconds)
    Await.result(pg2.initialize().runToFuture, 10 seconds)

    (pg1, pg2)
  }

  def random2TCPPeerGroup[M](
      implicit scheduler: Scheduler,
      codec: StreamCodec[M],
  ): (TCPPeerGroup[M], TCPPeerGroup[M]) = {
    val address = aRandomAddress()
    val address2 = aRandomAddress()

    val pg1 = new TCPPeerGroup(TCPPeerGroup.Config(address))
    val pg2 = new TCPPeerGroup(TCPPeerGroup.Config(address2))

    Await.result(pg1.initialize().runToFuture, 10 seconds)
    Await.result(pg2.initialize().runToFuture, 10 seconds)

    (pg1, pg2)
  }

  def random3TCPPeerGroup[M](
      implicit scheduler: Scheduler,
      codec: StreamCodec[M],
  ): (TCPPeerGroup[M], TCPPeerGroup[M], TCPPeerGroup[M]) = {
    val address = aRandomAddress()
    val address2 = aRandomAddress()
    val address3 = aRandomAddress()

    val pg1 = new TCPPeerGroup(TCPPeerGroup.Config(address))
    val pg2 = new TCPPeerGroup(TCPPeerGroup.Config(address2))
    val pg3 = new TCPPeerGroup(TCPPeerGroup.Config(address3))

    Await.result(pg1.initialize().runToFuture, 10 seconds)
    Await.result(pg2.initialize().runToFuture, 10 seconds)
    Await.result(pg3.initialize().runToFuture, 10 seconds)

    (pg1, pg2, pg3)
  }

  def withTwoRandomUDPPeerGroups[M](
      testCode: (UDPPeerGroup[M], UDPPeerGroup[M]) => Any
  )(implicit scheduler: Scheduler, codec: Codec[M]): Unit = {
    val pg1 = randomUDPPeerGroup
    val pg2 = randomUDPPeerGroup
    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }

  def withARandomUDPPeerGroup[M](
      testCode: UDPPeerGroup[M] => Any
  )(implicit scheduler: Scheduler, codec: Codec[M]): Unit = {
    val pg = randomUDPPeerGroup
    try {
      testCode(pg)
    } finally {
      pg.shutdown()
    }
  }
}
