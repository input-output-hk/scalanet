package io.iohk.scalanet.peergroup.dynamictls

import java.net.InetSocketAddress
import java.security._
import java.security.cert.X509Certificate
import cats.effect.Resource
import com.typesafe.scalalogging.StrictLogging
import io.iohk.scalanet.codec.StreamCodec
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.crypto.CryptoUtils.{SHA256withECDSA, Secp256r1}
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.{ProxySupport, ServerEvent, TerminalPeerGroup}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.SignedKeyExtensionNodeData
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.{Config, PeerInfo}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroupInternals.{ClientChannelImpl, ServerChannelBuilder}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroupUtils.{SSLContextForClient, SSLContextForServer}
import io.iohk.scalanet.peergroup.{Addressable, Channel, InetMultiAddress}
import io.iohk.scalanet.peergroup.CloseableQueue
import io.iohk.scalanet.peergroup.PeerGroup.ProxySupport.Socks5Config
import io.iohk.scalanet.peergroup.dynamictls.CustomHandlers.ThrottlingIpFilter
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import io.netty.handler.ssl.SslContext
import monix.eval.Task
import monix.execution.{ChannelType, Scheduler}
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import scodec.bits.BitVector

import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal

/**
  * PeerGroup implementation on top of TLS.
  * the encoded bytes provided by the callers codec are not identical to the bytes put on the wire (since a
  * length field is prepended to the byte stream). This class therefore cannot be used to talk to general services
  * that are not instances of TLSPeerGroup.
  *
  * @param config bind address etc. See the companion object.
  * @param codec  a decco codec for reading writing messages to NIO ByteBuffer.
  * @tparam M the message type.
  */
class DynamicTLSPeerGroup[M] private (val config: Config)(
    implicit codec: StreamCodec[M],
    scheduler: Scheduler
) extends TerminalPeerGroup[PeerInfo, M]
    with ProxySupport[PeerInfo, M]
    with StrictLogging {

  private val sslServerCtx: SslContext = DynamicTLSPeerGroupUtils.buildCustomSSlContext(SSLContextForServer, config)

  // Using MPMC because the channel creation event is only pushed after the SSL handshake,
  // which should take place on the channel thread, not the boss thread.
  private val serverQueue = CloseableQueue.unbounded[ServerEvent[PeerInfo, M]](ChannelType.MPMC).runSyncUnsafe()

  private val workerGroup = new NioEventLoopGroup()

  // throttling filter is shared between all incoming channels
  private val throttlingFilter = config.incomingConnectionsThrottling.map(cfg => new ThrottlingIpFilter(cfg))

  private val clientBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .option[Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)

  private val serverBootstrap = new ServerBootstrap()
    .group(workerGroup)
    .handler(new LoggingHandler(LogLevel.DEBUG))
    .channel(classOf[NioServerSocketChannel])
    .childHandler(new ChannelInitializer[SocketChannel]() {
      override def initChannel(ch: SocketChannel): Unit = {
        new ServerChannelBuilder[M](serverQueue, ch, sslServerCtx, codec.cleanSlate, throttlingFilter)
        logger.info(s"$processAddress received inbound from ${ch.remoteAddress()}.")
      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

  private lazy val serverBind: ChannelFuture = serverBootstrap.bind(config.bindAddress)

  private def initialize: Task[Unit] =
    toTask(serverBind).onErrorRecoverWith {
      case NonFatal(e) => Task.raiseError(InitializationError(e.getMessage, e.getCause))
    } *> Task(logger.info(s"Server bound to address ${config.bindAddress}"))

  override def processAddress: PeerInfo = config.peerInfo

  private def createChannel(to: PeerInfo, proxyConfig: Option[Socks5Config]): Resource[Task, Channel[PeerInfo, M]] = {
    // Creating new ssl context for each client is necessary, as this is only reliable way to pass peerInfo to TrustManager
    // which takes care of validating certificates and server node id.
    // Using Netty SSLEngine.getSession.putValue does not work as expected as until successfulhandshake there is no separate
    // session for each connection.
    Resource.make(
      Task.suspend {
        new ClientChannelImpl[M](
          to,
          clientBootstrap,
          DynamicTLSPeerGroupUtils.buildCustomSSlContext(SSLContextForClient(to), config),
          codec.cleanSlate,
          proxyConfig
        ).initialize
      }
    )(_.close())
  }

  override def client(to: PeerInfo): Resource[Task, Channel[PeerInfo, M]] = {
    createChannel(to, None)
  }

  override def client(to: PeerInfo, proxyConfig: Socks5Config): Resource[Task, Channel[PeerInfo, M]] = {
    createChannel(to, Some(proxyConfig))
  }

  override def nextServerEvent: Task[Option[ServerEvent[PeerInfo, M]]] =
    serverQueue.next

  private def shutdown: Task[Unit] = {
    for {
      _ <- Task(logger.debug("Start shutdown of tls peer group for peer {}", processAddress))
      _ <- serverQueue.close(discard = true)
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
      _ <- Task(logger.debug("Tls peer group shutdown for peer {}", processAddress))
    } yield ()
  }
}

object DynamicTLSPeerGroup {
  case class PeerInfo(id: BitVector, address: InetMultiAddress)
  object PeerInfo {
    implicit val peerInfoAddressable = new Addressable[PeerInfo] {
      override def getAddress(a: PeerInfo): InetSocketAddress = a.address.inetSocketAddress
    }
  }

  /**
    *
    * Config which enables specifying minimal duration between subsequent incoming connections attempts from the same
    * ip address
    *
    * @param throttleLocalhost if connections from localhost should also be throttled. Useful for test and testnets
    *                          when user want to quickly connects several peers to server
    * @param throttlingDuration minimal duration between subsequent incoming connections from same ip
    */
  case class IncomingConnectionThrottlingConfig(throttleLocalhost: Boolean, throttlingDuration: FiniteDuration)

  case class Config(
      bindAddress: InetSocketAddress,
      peerInfo: PeerInfo,
      connectionKeyPair: KeyPair,
      connectionCertificate: X509Certificate,
      incomingConnectionsThrottling: Option[IncomingConnectionThrottlingConfig]
  )

  object Config {
    // FIXME: For now we support only Secp256 keys in ethereum format
    def apply(
        bindAddress: InetSocketAddress,
        keyType: KeyType,
        hostKeyPair: KeyPair,
        secureRandom: SecureRandom,
        incomingConnectionsThrottling: Option[IncomingConnectionThrottlingConfig]
    ): Try[Config] = {

      SignedKeyExtensionNodeData(keyType, hostKeyPair, Secp256r1, secureRandom, SHA256withECDSA).map { nodeData =>
        Config(
          bindAddress,
          PeerInfo(nodeData.calculatedNodeId, InetMultiAddress(bindAddress)),
          nodeData.generatedConnectionKey,
          nodeData.certWithExtension,
          incomingConnectionsThrottling
        )
      }
    }

    def apply(
        bindAddress: InetSocketAddress,
        keyType: KeyType,
        hostKeyPair: AsymmetricCipherKeyPair,
        secureRandom: SecureRandom,
        incomingConnectionsThrottling: Option[IncomingConnectionThrottlingConfig]
    ): Try[Config] = {
      val convertedKeyPair = CryptoUtils.convertBcToJceKeyPair(hostKeyPair)
      Config(bindAddress, keyType, convertedKeyPair, secureRandom, incomingConnectionsThrottling)
    }
  }

  /** Create the peer group as a resource that is guaranteed to initialize itself and shut itself down at the end. */
  def apply[M: StreamCodec](config: Config)(implicit scheduler: Scheduler): Resource[Task, DynamicTLSPeerGroup[M]] =
    Resource.make {
      for {
        // NOTE: The DynamicTLSPeerGroup creates Netty workgroups in its constructor, so calling `shutdown` is a must.
        pg <- Task(new DynamicTLSPeerGroup[M](config))
        // NOTE: In theory we wouldn't have to initialize a peer group (i.e. start listening to incoming events)
        // if all we wanted was to connect to remote clients, however to clean up we must call `shutdown` at which point
        // it will start and stop the server anyway, and the interface itself suggests that one can always start concuming
        // server events, so this is cleaner semantics.
        _ <- pg.initialize
      } yield pg
    }(_.shutdown)

}
