package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.cert.Certificate
import java.security.{PrivateKey, PublicKey}
import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.scalanet.peergroup.DTLSPeerGroup.Config
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.subjects.{PublishSubject, ReplaySubject, Subject}
import org.eclipse.californium.elements._
import org.eclipse.californium.scandium.DTLSConnector
import org.eclipse.californium.scandium.config.DtlsConnectorConfig
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite._

import scala.collection.JavaConverters._
import scala.concurrent.Promise

class DTLSPeerGroup[M](val config: Config)(implicit codec: Codec[M], bufferInstantiator: BufferInstantiator[ByteBuffer], scheduler: Scheduler)
    extends PeerGroup[InetMultiAddress, M] {

  private val serverConnector = createServerConnector()

  private val channelSubject = PublishSubject[Channel[InetMultiAddress, M]]()

  private val activeChannels = new ConcurrentHashMap[(InetSocketAddress, InetSocketAddress), ChannelImpl]().asScala

  override def processAddress: InetMultiAddress = config.processAddress

  override def initialize(): Task[Unit] = {
    Task(serverConnector.start())
  }

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = {
    val connector = createClientConnector()
    connector.start()
    val id = (connector.getAddress, to.inetSocketAddress)
    assert(!activeChannels.contains(id), s"HOUSTON, WE HAVE A MULTIPLEXING PROBLEM")
    val channel = new ClientChannelImpl(to, connector)
    activeChannels.put(id, channel)
    Task(channel)
  }

  override def server(): Observable[Channel[InetMultiAddress, M]] = channelSubject

  override def shutdown(): Task[Unit] =
    for {
      _ <- Task(serverConnector.stop())
      _ <- Task(serverConnector.destroy())
    } yield ()

  private class ChannelImpl(val to: InetMultiAddress, dtlsConnector: DTLSConnector)(implicit codec: Codec[M])
      extends Channel[InetMultiAddress, M] {

    override val in: Subject[M, M] = ReplaySubject[M]()

    override def sendMessage(message: M): Task[Unit] = {
      import io.iohk.scalanet.peergroup.BufferConversionOps._
      val buffer = codec.encode(message)

      val promisedSend = Promise[Unit]()

      val callback = new MessageCallback {
        override def onConnecting(): Unit = ()

        override def onDtlsRetransmission(i: Int): Unit = ()

        override def onContextEstablished(endpointContext: EndpointContext): Unit = ()

        override def onSent(): Unit = promisedSend.success(())

        override def onError(throwable: Throwable): Unit = promisedSend.failure(throwable)
      }

      val rawData = RawData.outbound(buffer.toArray, new AddressEndpointContext(to.inetSocketAddress), callback, false)
      dtlsConnector.send(rawData)
      Task.fromFuture(promisedSend.future)
    }

    override def close(): Task[Unit] = {
      val id = (dtlsConnector.getAddress, to.inetSocketAddress)
      activeChannels(id).in.onComplete()
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
    val connectorConfig = new DtlsConnectorConfig.Builder()
      .setAddress(new InetSocketAddress(config.processAddress.inetSocketAddress.getAddress, 0))
      .setIdentity(config.privateKey, config.publicKey)
      .setTrustStore(config.trustStore.toArray)
      .setRpkTrustAll()
      .setClientOnly()
      .build()

    val connector = new DTLSConnector(connectorConfig)

    connector.setRawDataReceiver(new RawDataChannel {
      override def receiveData(rawData: RawData): Unit = {
        val channelId = (connector.getAddress, rawData.getInetSocketAddress)

        assert(activeChannels.contains(channelId), s"Missing channel for channelId $channelId")

        val activeChannel: ChannelImpl = activeChannels(channelId)

        val messageE = codec.decode(ByteBuffer.wrap(rawData.bytes))

        messageE.foreach(message => activeChannel.in.onNext(message))
      }
    })

    connector
  }

  private def createServerConnector(): DTLSConnector = {
    val connectorConfig = new DtlsConnectorConfig.Builder()
      .setAddress(config.bindAddress)
      .setSupportedCipherSuites(
        TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8,
        TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8,
        TLS_ECDHE_ECDSA_WITH_AES_128_CCM,
        TLS_ECDHE_ECDSA_WITH_AES_256_CCM,
        TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
        TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
        TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384
      )
      .setIdentity(config.privateKey, config.publicKey)
      .setTrustStore(config.trustStore.toArray)
      .setClientAuthenticationRequired(config.clientAuthRequired)
      .setRpkTrustAll()
      .build()

    val connector = new DTLSConnector(connectorConfig)

    connector.setRawDataReceiver(new RawDataChannel {
      override def receiveData(rawData: RawData): Unit = {
        val channelId = (processAddress.inetSocketAddress, rawData.getInetSocketAddress)

        val activeChannel: ChannelImpl = activeChannels.getOrElseUpdate(channelId, createNewChannel(rawData))

        val messageE = codec.decode(ByteBuffer.wrap(rawData.bytes))

        messageE.foreach(message => activeChannel.in.onNext(message))
      }

      private def createNewChannel(rawData: RawData): ChannelImpl = {
        val newChannel = new ChannelImpl(InetMultiAddress(rawData.getInetSocketAddress), connector)
        channelSubject.onNext(newChannel)
        newChannel
      }
    })

    connector
  }
}

object DTLSPeerGroup {

  case class Config(
      bindAddress: InetSocketAddress,
      processAddress: InetMultiAddress,
      publicKey: PublicKey,
      privateKey: PrivateKey,
      trustStore: List[Certificate],
      clientAuthRequired: Boolean
  )

  object Config {
    def apply(
        bindAddress: InetSocketAddress,
        publicKey: PublicKey,
        privateKey: PrivateKey
    ): Config =
      Config(
        bindAddress,
        InetMultiAddress(bindAddress),
        publicKey,
        privateKey,
        trustStore = Nil,
        clientAuthRequired = false
      )

    def apply(
        bindAddress: InetSocketAddress,
        publicKey: PublicKey,
        privateKey: PrivateKey,
        trustStore: List[Certificate],
        clientAuthRequired: Boolean = true
    ): Config =
      Config(bindAddress, InetMultiAddress(bindAddress), publicKey, privateKey, trustStore, clientAuthRequired)
  }

}
