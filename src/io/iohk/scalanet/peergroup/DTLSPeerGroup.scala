package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.cert.Certificate
import java.security.{PrivateKey, PublicKey}
import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco.Codec
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

class DTLSPeerGroup[M](val config: Config)(implicit codec: Codec[M], scheduler: Scheduler)
    extends PeerGroup[InetMultiAddress, M] {

  private val connector = createConnector()

  private val channelSubject = PublishSubject[Channel[InetMultiAddress, M]]()

  private val activeChannels = new ConcurrentHashMap[(InetSocketAddress, InetSocketAddress), ChannelImpl]().asScala

  override def processAddress: InetMultiAddress = config.processAddress

  override def initialize(): Task[Unit] = {
    Task(connector.start())
  }

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = {
    val id = channelId(to)
    assert(!activeChannels.contains(id), s"HOUSTON, WE HAVE A MULTIPLEXING PROBLEM")
    val channel = new ChannelImpl(to, connector)
    activeChannels.put(id, channel)
    Task(channel)
  }

  override def server(): Observable[Channel[InetMultiAddress, M]] = channelSubject

  override def shutdown(): Task[Unit] =
    for {
      _ <- Task(connector.stop())
      _ <- Task(connector.destroy())
    } yield ()

  private def channelId(to: InetMultiAddress): (InetSocketAddress, InetSocketAddress) = {
    (processAddress.inetSocketAddress, to.inetSocketAddress)
  }

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
      val id = channelId(to)
      activeChannels.get(id).foreach(channel => channel.in.onComplete())
      activeChannels.remove(id)
      Task.unit
    }
  }

  private def createConnector(): DTLSConnector = {
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
