package io.iohk.scalanet.peergroup.dynamictls

import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate

import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.PeerInfo
import io.netty.handler.ssl.util.SimpleTrustManagerFactory
import io.netty.handler.ssl.{ClientAuth, SslContext, SslContextBuilder}
import javax.net.ssl._
import scodec.bits.BitVector

import scala.collection.JavaConverters._

private[scalanet] object DynamicTLSPeerGroupUtils {
  // supporting only ciphers which are used in TLS 1.3
  val supportedCipherSuites: Seq[String] = Seq(
    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
  )

  val typeOfIdentificaiton = 0

  class IdIdentification(bytes: Array[Byte]) extends SNIServerName(typeOfIdentificaiton, bytes)

  class DynamicTlsTrustManager(info: Option[BitVector]) extends X509ExtendedTrustManager {
    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ???

    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ???

    override def getAcceptedIssuers: Array[X509Certificate] = {
      new Array[X509Certificate](0)
    }

    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String, socket: Socket): Unit = ???

    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String, sslEngine: SSLEngine): Unit = {
      CustomTlsValidator.validateCertificates(x509Certificates, info) match {
        case Left(er) => throw er
        case Right(value) =>
          val id = value.publicKey.getNodeId
          val params = sslEngine.getSSLParameters
          val listPars: List[SNIServerName] = List(new IdIdentification(id.toByteArray))
          // A little hack to pass client id to DynamicTlsPeerGroup.
          params.setServerNames(listPars.asJava)
          sslEngine.setSSLParameters(params)
      }
    }

    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String, socket: Socket): Unit = ???

    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String, sslEngine: SSLEngine): Unit = {
      CustomTlsValidator.validateCertificates(x509Certificates, info) match {
        case Left(er) => throw er
        case Right(_) => ()
      }
    }
  }

  class CustomTrustManagerFactory(info: Option[BitVector]) extends SimpleTrustManagerFactory {

    private val tm = new DynamicTlsTrustManager(info)

    override def engineGetTrustManagers(): Array[TrustManager] = {
      Array[TrustManager] { tm }
    }

    override def engineInit(keyStore: KeyStore): Unit = {}

    override def engineInit(managerFactoryParameters: ManagerFactoryParameters): Unit = {}
  }

  sealed trait SSLContextFor
  case object SSLContextForSever extends SSLContextFor
  case class SSLContextForClient(to: PeerInfo) extends SSLContextFor

  def buildCustomSSlContext(f: SSLContextFor, config: DynamicTLSPeerGroup.Config): SslContext = {
    f match {
      case SSLContextForSever =>
        SslContextBuilder
          .forServer(config.connectionKeyPair.getPrivate, List(config.connectionCertificate): _*)
          .trustManager(new CustomTrustManagerFactory(None))
          .clientAuth(ClientAuth.REQUIRE)
          .ciphers(supportedCipherSuites.asJava)
          .protocols("TLSv1.2")
          .build()

      case SSLContextForClient(info) =>
        SslContextBuilder
          .forClient()
          .keyManager(config.connectionKeyPair.getPrivate, List(config.connectionCertificate): _*)
          .trustManager(new CustomTrustManagerFactory(Some(info.id)))
          .ciphers(supportedCipherSuites.asJava)
          .protocols("TLSv1.2")
          .build()
    }
  }

}
