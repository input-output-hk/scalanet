package io.iohk.scalanet

import java.net._
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.Certificate
import io.iohk.scalanet.peergroup.InetPeerGroupUtils

import scala.util.Random
import scala.collection.mutable.ArrayBuffer

object NetUtils {

  val keyStore: KeyStore = loadKeyStore("keystore.p12", "password")
  val trustStore: KeyStore = loadKeyStore("truststore.p12", "password")
  val trustedCerts: Array[Certificate] = { 
    val aliases = trustStore.aliases()
    val buffer = ArrayBuffer[Certificate]()
    while(aliases.hasMoreElements()) {
      val alias = aliases.nextElement()
      buffer.append(trustStore.getCertificate(alias))
    }
    buffer.toArray
  }

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
}
