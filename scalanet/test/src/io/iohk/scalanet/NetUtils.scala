package io.iohk.scalanet

import java.net._
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.Certificate
import cats.effect.Resource
import io.iohk.scalanet.peergroup.InetPeerGroupUtils
import io.iohk.scalanet.peergroup.udp.DynamicUDPPeerGroup
import monix.execution.Scheduler
import monix.eval.Task
import scodec.Codec

import scala.collection.JavaConverters._
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
      ()
    } finally {
      socket.close()
    }
  }

  def withUDPAddressInUse(testCode: InetSocketAddress => Any): Unit = {
    val socket = new DatagramSocket()
    val address = socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]
    try {
      testCode(address)
      ()
    } finally {
      socket.close()
    }
  }

  def randomBytes(n: Int): Array[Byte] = {
    val a = new Array[Byte](n)
    Random.nextBytes(a)
    a
  }

  def randomUDPPeerGroup[M](
      implicit scheduler: Scheduler,
      codec: Codec[M]
  ): Resource[Task, DynamicUDPPeerGroup[M]] =
    DynamicUDPPeerGroup(DynamicUDPPeerGroup.Config(aRandomAddress()))

  def withTwoRandomUDPPeerGroups[M](
      testCode: (DynamicUDPPeerGroup[M], DynamicUDPPeerGroup[M]) => Task[_]
  )(implicit scheduler: Scheduler, codec: Codec[M]): Unit = {
    (for {
      pg1 <- randomUDPPeerGroup
      pg2 <- randomUDPPeerGroup
    } yield (pg1, pg2))
      .use {
        case (pg1, pg2) =>
          testCode(pg1, pg2).void
      }
      .runSyncUnsafe(15.seconds)
  }

  def withARandomUDPPeerGroup[M](
      testCode: DynamicUDPPeerGroup[M] => Task[_]
  )(implicit scheduler: Scheduler, codec: Codec[M]): Unit = {
    randomUDPPeerGroup
      .use { pg =>
        testCode(pg).void
      }
      .runSyncUnsafe(15.seconds)
  }
}
