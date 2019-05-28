package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.{PrivateKey, PublicKey}
import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.DTLSPeerGroup.Config
import monix.eval.Task
import monix.reactive.Observable
import monix.reactive.subjects.{PublishSubject, ReplaySubject, Subject}
import org.eclipse.californium.scandium.DTLSConnector
import org.eclipse.californium.scandium.config.DtlsConnectorConfig
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite
import org.eclipse.californium.elements.{RawData, RawDataChannel}

import scala.collection.JavaConverters._

class DTLSPeerGroup[M](val config: Config)(implicit codec: Codec[M]) extends PeerGroup[InetMultiAddress, M] {

  private val scandiumDTLSConnectorConfig = new DtlsConnectorConfig.Builder(config.bindAddress)
    .setSupportedCipherSuites(
      Array[CipherSuite](
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256
      )
    )
    .setIdentity(config.privateKey, config.publicKey)
    .setClientAuthenticationRequired(config.clientAuthRequired)
    .build()

  private val scandiumDTLSConnector = new DTLSConnector(scandiumDTLSConnectorConfig)

  private val channelSubject = PublishSubject[Channel[InetMultiAddress, M]]()

  private val activeChannels = new ConcurrentHashMap[(InetSocketAddress, InetSocketAddress), ChannelImpl]().asScala

  override def processAddress: InetMultiAddress = config.processAddress

  override def initialize(): Task[Unit] = {
    Task(scandiumDTLSConnector.start())
  }

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = {
    /*
        val cf = clientBootstrap.connect(to.inetSocketAddress)
    val ct: Task[NioDatagramChannel] = toTask(cf).map(_ => cf.channel().asInstanceOf[NioDatagramChannel])
    ct.map { nettyChannel =>
      val localAddress = nettyChannel.localAddress()
      log.debug(s"Generated local address for new client is $localAddress")
      val channelId = getChannelId(to.inetSocketAddress, localAddress)

      assert(!activeChannels.contains(channelId), s"HOUSTON, WE HAVE A MULTIPLEXING PROBLEM")

      val channel = new ChannelImpl(nettyChannel, localAddress, to.inetSocketAddress, ReplaySubject[M]())
      activeChannels.put(channelId, channel)
      channel
    }
     */
    val id = channelId(to)
    assert(!activeChannels.contains(id), s"HOUSTON, WE HAVE A MULTIPLEXING PROBLEM")
    val channel = new ChannelImpl(to, scandiumDTLSConnector)
    activeChannels.put(id, channel)
    Task(channel)
  }

  override def server(): Observable[Channel[InetMultiAddress, M]] = channelSubject

  override def shutdown(): Task[Unit] =
    for {
      _ <- Task(scandiumDTLSConnector.stop())
      _ <- Task(scandiumDTLSConnector.destroy())
    } yield ()

  scandiumDTLSConnector.setRawDataReceiver(new RawDataChannel {
    override def receiveData(rawData: RawData): Unit = {
      val channelId = (processAddress.inetSocketAddress, rawData.getInetSocketAddress)

      val activeChannel: ChannelImpl = activeChannels.getOrElseUpdate(channelId, createNewChannel(rawData))

      val messageE = codec.decode(ByteBuffer.wrap(rawData.bytes))

      messageE.foreach(message => activeChannel.in.onNext(message))
    }

    private def createNewChannel(rawData: RawData): ChannelImpl = {
      val newChannel = new ChannelImpl(InetMultiAddress(rawData.getInetSocketAddress), scandiumDTLSConnector)
      channelSubject.onNext(newChannel)
      newChannel
    }
  })

  private def channelId(to: InetMultiAddress): (InetSocketAddress, InetSocketAddress) = {
    (processAddress.inetSocketAddress, to.inetSocketAddress)
  }

  class ChannelImpl(val to: InetMultiAddress, dtlsConnector: DTLSConnector)(implicit codec: Codec[M])
      extends Channel[InetMultiAddress, M] {

    override val in: Subject[M, M] = ReplaySubject[M]()

    override def sendMessage(message: M): Task[Unit] = {
      import io.iohk.scalanet.peergroup.BufferConversionOps._
      val buffer = codec.encode(message)
      Task(dtlsConnector.send(new RawData(buffer.toArray, to.inetSocketAddress)))
    }

    override def close(): Task[Unit] = {
      val id = channelId(to)
      activeChannels.get(id).foreach(channel => channel.in.onComplete())
      activeChannels.remove(id)
      Task.unit
    }
  }

}

object DTLSPeerGroup {

  case class Config(
      bindAddress: InetSocketAddress,
      processAddress: InetMultiAddress,
      publicKey: PublicKey,
      privateKey: PrivateKey,
      clientAuthRequired: Boolean
  )

  object Config {
    def apply(
        bindAddress: InetSocketAddress,
        publicKey: PublicKey,
        privateKey: PrivateKey,
        clientAuthRequired: Boolean = false
    ): Config =
      Config(bindAddress, InetMultiAddress(bindAddress), publicKey, privateKey, clientAuthRequired)
  }

}
