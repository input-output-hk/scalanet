package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.cert.Certificate
import java.security.{PrivateKey, PublicKey}
import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.DTLSPeerGroup.Config
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.{ChannelId, _}
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.PeerGroup._
import monix.eval.Task
import monix.execution.{Callback, Scheduler}
import monix.reactive.observables.ConnectableObservable
import org.eclipse.californium.elements._
import org.eclipse.californium.scandium.DTLSConnector
import org.eclipse.californium.scandium.config.DtlsConnectorConfig
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite._
import org.eclipse.californium.scandium.dtls.{HandshakeException, Handshaker, SessionAdapter}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

class DTLSPeerGroup[M](val config: Config)(
    implicit codec: Codec[M],
    bufferInstantiator: BufferInstantiator[ByteBuffer],
    scheduler: Scheduler
) extends PeerGroup[InetMultiAddress, M] {

  private val serverConnector = createServerConnector()
  private val channelSubject = ConnectableSubject[ServerEvent[InetMultiAddress, M]]()

  private val activeChannels = new ConcurrentHashMap[ChannelId, ChannelImpl]().asScala

  override def processAddress: InetMultiAddress = config.processAddress

  override def initialize(): Task[Unit] = {
    Task(serverConnector.start()).onErrorRecoverWith {
      case NonFatal(e) => Task.raiseError(InitializationError(e.getMessage, e.getCause))
    }
  }

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = Task {
    val connector = createClientConnector()
    connector.start()
    val id = getChannelId(connector.getAddress, to.inetSocketAddress)
    assert(!activeChannels.contains(id), s"HOUSTON, WE HAVE A MULTIPLEXING PROBLEM")
    val channel = new ClientChannelImpl(to, connector)
    activeChannels.put(id, channel)
    channel
  }

  override def server(): ConnectableObservable[ServerEvent[InetMultiAddress, M]] = channelSubject

  override def shutdown(): Task[Unit] =
    for {
      _ <- Task(serverConnector.stop())
      _ <- Task(serverConnector.destroy())
    } yield ()

  private class ChannelImpl(val to: InetMultiAddress, dtlsConnector: DTLSConnector)(implicit codec: Codec[M])
      extends Channel[InetMultiAddress, M] {

    val channelSubject = ConnectableSubject[M]()

    override val in: ConnectableObservable[M] = channelSubject

    override def sendMessage(message: M): Task[Unit] = {
      import io.iohk.scalanet.peergroup.BufferConversionOps._
      val buffer = codec.encode(message)

      Task
        .async[Unit] { cb: Callback[Throwable, Unit] =>
          val messageCallback = new MessageCallback {
            override def onConnecting(): Unit = ()
            override def onDtlsRetransmission(i: Int): Unit = ()
            override def onContextEstablished(endpointContext: EndpointContext): Unit = ()

            override def onSent(): Unit = cb.onSuccess(())
            override def onError(throwable: Throwable): Unit = throwable match {
              case h: HandshakeException =>
                cb.onError(new PeerGroup.HandshakeException[InetMultiAddress](to, h))
              case _: IllegalArgumentException =>
                cb.onError(new MessageMTUException[InetMultiAddress](to, buffer.capacity()))
            }
          }

          val rawData =
            RawData.outbound(buffer.toArray, new AddressEndpointContext(to.inetSocketAddress), messageCallback, false)

          dtlsConnector.send(rawData)

        }
    }

    override def close(): Task[Unit] = {
      val id = (dtlsConnector.getAddress, to.inetSocketAddress)
      activeChannels(id).channelSubject.onComplete()
      activeChannels.remove(id)
      Task.unit
    }
  }

  private class ClientChannelImpl(to: InetMultiAddress, dtlsConnector: DTLSConnector)(implicit codec: Codec[M])
      extends ChannelImpl(to, dtlsConnector) {
    override def close(): Task[Unit] = {
      dtlsConnector.stop()
      dtlsConnector.destroy()
      super.close()
    }
  }

  private def createClientConnector(): DTLSConnector = {
    val connectorConfig = config.scandiumConfigBuilder
      .setAddress(new InetSocketAddress(config.processAddress.inetSocketAddress.getAddress, 0))
      .setClientOnly()
      .build()

    val connector = new DTLSConnector(connectorConfig)

    connector.setRawDataReceiver((rawData: RawData) => {
      val channelId = getChannelId(connector.getAddress, rawData.getInetSocketAddress)

      assert(activeChannels.contains(channelId), s"Missing channel for channelId $channelId")

      val activeChannel: ChannelImpl = activeChannels(channelId)

      val messageE = codec.decode(ByteBuffer.wrap(rawData.bytes))

      messageE.foreach(message => activeChannel.channelSubject.onNext(message))
    })

    connector
  }

  private def createServerConnector(): DTLSConnector = {
    val connectorConfig = config.scandiumConfigBuilder.build()

    val connector = new DTLSConnector(connectorConfig) {
      override def onInitializeHandshaker(handshaker: Handshaker): Unit = {
        super.onInitializeHandshaker(handshaker)
        handshaker.addSessionListener(new SessionAdapter() {
          override def handshakeFailed(handshaker: Handshaker, error: Throwable): Unit = {
            channelSubject.onNext(
              ServerEvent
                .HandshakeFailed(new PeerGroup.HandshakeException(InetMultiAddress(handshaker.getPeerAddress), error))
            )
          }
        })
      }
    }

    connector.setRawDataReceiver(new RawDataChannel {
      override def receiveData(rawData: RawData): Unit = {
        val channelId = getChannelId(processAddress.inetSocketAddress, rawData.getInetSocketAddress)

        val activeChannel: ChannelImpl = activeChannels.getOrElseUpdate(channelId, createNewChannel(rawData))

        val messageE = codec.decode(ByteBuffer.wrap(rawData.bytes))

        messageE.foreach(message => activeChannel.channelSubject.onNext(message))
      }

      private def createNewChannel(rawData: RawData): ChannelImpl = {
        val newChannel = new ChannelImpl(InetMultiAddress(rawData.getInetSocketAddress), connector)
        channelSubject.onNext(ChannelCreated(newChannel))
        newChannel
      }
    })

    connector
  }
}

object DTLSPeerGroup {

  val supportedCipherSuites = Seq(
    TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
    TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8,
    TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8,
    TLS_ECDHE_ECDSA_WITH_AES_128_CCM,
    TLS_ECDHE_ECDSA_WITH_AES_256_CCM,
    TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
    TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
    TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384
  )

  trait Config {
    val bindAddress: InetSocketAddress
    val processAddress: InetMultiAddress
    private[scalanet] def scandiumConfigBuilder: DtlsConnectorConfig.Builder
  }

  object Config {

    case class Unauthenticated(
        bindAddress: InetSocketAddress,
        processAddress: InetMultiAddress,
        publicKey: PublicKey,
        privateKey: PrivateKey
    ) extends Config {
      override def scandiumConfigBuilder: DtlsConnectorConfig.Builder =
        new DtlsConnectorConfig.Builder()
          .setAddress(bindAddress)
          .setSupportedCipherSuites(supportedCipherSuites: _*)
          .setIdentity(privateKey, publicKey)
          .setRpkTrustAll()
    }

    object Unauthenticated {
      def apply(
          bindAddress: InetSocketAddress,
          publicKey: PublicKey,
          privateKey: PrivateKey
      ): Unauthenticated =
        Unauthenticated(
          bindAddress,
          InetMultiAddress(bindAddress),
          publicKey,
          privateKey
        )
    }

    /*
    certificate_list
      This is a sequence (chain) of certificates.  The sender's
      certificate MUST come first in the list.  Each following
      certificate MUST directly certify the one preceding it.  Because
      certificate validation requires that root keys be distributed
      independently, the self-signed certificate that specifies the root
      certificate authority MAY be omitted from the chain, under the
      assumption that the remote end must already possess it in order to
      validate it in any case.
     */
    case class CertAuthenticated(
        bindAddress: InetSocketAddress,
        processAddress: InetMultiAddress,
        certificateChain: Array[Certificate],
        privateKey: PrivateKey,
        trustedCerts: Array[Certificate]
    ) extends Config {
      override def scandiumConfigBuilder: DtlsConnectorConfig.Builder = {
        new DtlsConnectorConfig.Builder()
          .setAddress(bindAddress)
          .setSupportedCipherSuites(supportedCipherSuites: _*)
          .setIdentity(privateKey, certificateChain)
          .setTrustStore(trustedCerts)
      }
    }

    object CertAuthenticated {
      def apply(
          bindAddress: InetSocketAddress,
          certificateChain: Array[Certificate],
          privateKey: PrivateKey,
          trustedCerts: Array[Certificate]
      ): CertAuthenticated =
        CertAuthenticated(bindAddress, InetMultiAddress(bindAddress), certificateChain, privateKey, trustedCerts)
    }
  }
}
