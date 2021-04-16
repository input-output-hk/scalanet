package io.iohk.scalanet.peergroup.dynamictls

import java.net.InetSocketAddress
import java.security._
import java.security.cert.X509Certificate
import cats.effect.Resource
import com.typesafe.scalalogging.StrictLogging
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.crypto.CryptoUtils.{SHA256withECDSA, Secp256r1}
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.NettyFutureUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.{ProxySupport, ServerEvent, TerminalPeerGroup}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.SignedKeyExtensionNodeData
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.{Config, PeerInfo}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroupInternals.{ClientChannelBuilder, ServerChannelBuilder}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroupUtils.{SSLContextForClient, SSLContextForServer}
import io.iohk.scalanet.peergroup.{Addressable, Channel, InetMultiAddress}
import io.iohk.scalanet.peergroup.CloseableQueue
import io.iohk.scalanet.peergroup.PeerGroup.ProxySupport.Socks5Config
import io.iohk.scalanet.peergroup.dynamictls.CustomHandlers.ThrottlingIpFilter
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.FramingConfig.ValidLengthFieldLength
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
import scodec.Codec
import scodec.bits.BitVector

import java.nio.ByteOrder
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
  * @param codec  a codec for reading writing messages to NIO ByteBuffer.
  * @tparam M the message type.
  */
class DynamicTLSPeerGroup[M] private (
    val config: Config,
    serverQueue: CloseableQueue[ServerEvent[PeerInfo, M]]
)(
    implicit codec: Codec[M],
    scheduler: Scheduler
) extends TerminalPeerGroup[PeerInfo, M]
    with ProxySupport[PeerInfo, M]
    with StrictLogging {

  private val sslServerCtx: SslContext = DynamicTLSPeerGroupUtils.buildCustomSSlContext(SSLContextForServer, config)

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
        new ServerChannelBuilder[M](
          config.peerInfo.id,
          serverQueue,
          ch,
          sslServerCtx,
          config.framingConfig,
          config.maxIncomingMessageQueueSize,
          throttlingFilter,
          config.stalePeerDetectionConfig
        )
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
        new ClientChannelBuilder[M](
          config.peerInfo.id,
          to,
          clientBootstrap,
          DynamicTLSPeerGroupUtils.buildCustomSSlContext(SSLContextForClient(to), config),
          config.framingConfig,
          config.maxIncomingMessageQueueSize,
          proxyConfig,
          config.stalePeerDetectionConfig
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

  final case class ConfigError(description: String)

  sealed abstract case class FramingConfig private (
      maxFrameLength: Int,
      lengthFieldOffset: Int,
      lengthFieldLength: ValidLengthFieldLength,
      encodingLengthAdjustment: Int,
      decodingLengthAdjustment: Int,
      initialBytesToStrip: Int,
      failFast: Boolean,
      byteOrder: ByteOrder,
      lengthIncludesLengthFieldLength: Boolean
  )

  object FramingConfig {
    sealed abstract class ValidLengthFieldLength {
      def value: Int
    }
    case object SingleByteLength extends ValidLengthFieldLength {
      val value = 1
    }
    case object TwoByteLength extends ValidLengthFieldLength {
      val value = 2
    }
    case object ThreeByteLength extends ValidLengthFieldLength {
      val value = 3
    }
    case object FourByteLength extends ValidLengthFieldLength {
      val value = 4
    }
    case object EightByteLength extends ValidLengthFieldLength {
      val value = 8
    }

    object ValidLengthFieldLength {
      def apply(i: Int): Either[ConfigError, ValidLengthFieldLength] = {
        i match {
          case 1 => Right(SingleByteLength)
          case 2 => Right(TwoByteLength)
          case 3 => Right(ThreeByteLength)
          case 4 => Right(FourByteLength)
          case 8 => Right(EightByteLength)
          case _ => Left(ConfigError("lengthFieldLength should be one of (1, 2, 3, 4, 8)"))
        }
      }
    }

    private def check(test: Boolean, message: String): Either[ConfigError, Unit] = {
      Either.cond(test, (), ConfigError(message))
    }

    /**
      *
      * Configures framing format for all the peers in PeerGroup
      *
      * Check [[io.netty.handler.codec.LengthFieldPrepender]] and [[io.netty.handler.codec.LengthFieldBasedFrameDecoder]]
      * for good description of all the fields
      *
      * @param maxFrameLength the maximum length of the frame. If the length of the frame is greater than this value
      *                       channel with generate DecodingError event.
      * @param lengthFieldOffset the offset of the length field.
      * @param lengthFieldLength  the length of the length field. Must be 1, 2, 3, 4 or 8 bytes.
      * @param decodingLengthAdjustment  the compensation value to add to the value of the length field when decoding.
      * @param encodingLengthAdjustment the compensation value to add to the value of the length field when encoding.
      * @param initialBytesToStrip the number of first bytes to strip out from the decoded frame. In standard framing setup
      *                            it should be equal to lengthFieldLength.
      * @param failFast  If true, a DecodingError event is generated as soon as the decoder notices the length of the frame will
      *                  exceed maxFrameLength regardless of whether the entire frame has been read. If false,
      *                  a DecodingError event is generated after the entire frame that exceeds maxFrameLength has been read.
      * @param byteOrder the ByteOrder of the length field.
      * @param lengthIncludesLengthFieldLength if true, the length of the prepended length field is added to the value of the prepended length field.
      */
    def buildConfig(
        maxFrameLength: Int,
        lengthFieldOffset: Int,
        lengthFieldLength: Int,
        initialBytesToStrip: Int,
        encodingLengthAdjustment: Int = 0,
        decodingLengthAdjustment: Int = 0,
        failFast: Boolean = true,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        lengthIncludesLengthFieldLength: Boolean = false
    ): Either[ConfigError, FramingConfig] = {
      def validateLengthFieldLengthWithMaxFrame(
          lengthFieldLength: ValidLengthFieldLength
      ): Either[ConfigError, Unit] = {
        val prefixAdjustment = if (lengthIncludesLengthFieldLength) lengthFieldLength.value else 0

        val maximalMessageLength = maxFrameLength + encodingLengthAdjustment + prefixAdjustment

        lengthFieldLength match {
          case SingleByteLength if maximalMessageLength >= 256 =>
            Left(ConfigError(s"length $maximalMessageLength does not fit into a byte"))
          case TwoByteLength if maximalMessageLength >= 65536 =>
            Left(ConfigError(s"length $maximalMessageLength does not fit into a short integer"))
          case ThreeByteLength if maximalMessageLength >= 16777216 =>
            Left(ConfigError(s"length $maximalMessageLength does not fit into a medium integer"))
          case _ => Right(())
        }
      }

      val smallEnoughOffset = lengthFieldOffset <= maxFrameLength - lengthFieldLength
      for {
        _ <- check(maxFrameLength > 0, "maxFrameLength should be positive")
        _ <- check(lengthFieldOffset >= 0, "lengthFieldOffset should be non negative")
        _ <- check(initialBytesToStrip >= 0, "initialBytesToStrip should be non negative")
        validLengthField <- ValidLengthFieldLength(lengthFieldLength)
        _ <- validateLengthFieldLengthWithMaxFrame(validLengthField)
        _ <- check(
          smallEnoughOffset,
          "lengthFieldOffset should be smaller or equal (maxFrameLength - lengthFieldLength)"
        )

      } yield {
        new FramingConfig(
          maxFrameLength,
          lengthFieldOffset,
          validLengthField,
          encodingLengthAdjustment,
          decodingLengthAdjustment,
          initialBytesToStrip,
          failFast,
          byteOrder,
          lengthIncludesLengthFieldLength
        ) {}
      }
    }

    /**
      *
      * Configures framing format for all the peers in PeerGroup, which will prepend length to all messages when encoding
      * and strip this length when decoding.
      *
      * @param maxFrameLength the maximum length of the frame. If the length of the frame is greater than this value
      *                       channel with generate DecodingError event.
      * @param lengthFieldLength  the length of the length field. Must be 1, 2, 3, 4 or 8 bytes.
      */
    def buildStandardFrameConfig(
        maxFrameLength: Int,
        lengthFieldLength: Int
    ): Either[ConfigError, FramingConfig] = {
      buildConfig(maxFrameLength, 0, lengthFieldLength, lengthFieldLength)
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

  /**
    * Configures detection on idle peer
    * @param readerIdleTime a ChannelIdle event whose state is ReaderIdle will be triggered when
    *                       no read was performed for the specified period of time on given channel. Specify 0 to disable.
    * @param writerIdleTime a ChannelIdle event whose state is WriterIdle will be triggered when
    *                       no write was performed for the specified period of time on given channel. Specify 0 to disable.
    * @param allIdleTime a ChannelIdle event whose state is AllIdle will be triggered when
    *                      neither read nor write was performed for the specified period of time on given channel. Specify 0 to disable.
    *
    */
  case class StalePeerDetectionConfig(
      readerIdleTime: FiniteDuration,
      writerIdleTime: FiniteDuration,
      allIdleTime: FiniteDuration
  )

  /**
    *
    * Configuration for DynamicTlsPeerGroup with all possible options
    *
    * @param bindAddress the interface to which server should be bind
    * @param peerInfo local id of the peer and server address
    * @param connectionKeyPair keyPair used in negotiating tls connections
    * @param connectionCertificate  connection certificate of local node
    * @param useNativeTlsImplementation should native or java tls implementation be used
    * @param framingConfig details about framing on the wire
    * @param maxIncomingMessageQueueSize max number of un-read messages per remote peer
    * @param incomingConnectionsThrottling optional possibility to throttle incoming connections
    * @param stalePeerDetectionConfig optional possibility to detect if remote peer is idle
    */
  case class Config(
      bindAddress: InetSocketAddress,
      peerInfo: PeerInfo,
      connectionKeyPair: KeyPair,
      connectionCertificate: X509Certificate,
      useNativeTlsImplementation: Boolean,
      framingConfig: FramingConfig,
      maxIncomingMessageQueueSize: Int,
      incomingConnectionsThrottling: Option[IncomingConnectionThrottlingConfig],
      stalePeerDetectionConfig: Option[StalePeerDetectionConfig]
  )

  object Config {
    // FIXME: For now we support only Secp256 keys in ethereum format
    def apply(
        bindAddress: InetSocketAddress,
        keyType: KeyType,
        hostKeyPair: KeyPair,
        secureRandom: SecureRandom,
        useNativeTlsImplementation: Boolean,
        framingConfig: FramingConfig,
        maxIncomingMessageQueueSize: Int,
        incomingConnectionsThrottling: Option[IncomingConnectionThrottlingConfig],
        stalePeerDetectionConfig: Option[StalePeerDetectionConfig]
    ): Try[Config] = {

      SignedKeyExtensionNodeData(keyType, hostKeyPair, Secp256r1, secureRandom, SHA256withECDSA).map { nodeData =>
        Config(
          bindAddress,
          PeerInfo(nodeData.calculatedNodeId, InetMultiAddress(bindAddress)),
          nodeData.generatedConnectionKey,
          nodeData.certWithExtension,
          useNativeTlsImplementation,
          framingConfig,
          maxIncomingMessageQueueSize: Int,
          incomingConnectionsThrottling,
          stalePeerDetectionConfig
        )
      }
    }

    def apply(
        bindAddress: InetSocketAddress,
        keyType: KeyType,
        hostKeyPair: AsymmetricCipherKeyPair,
        secureRandom: SecureRandom,
        useNativeTlsImplementation: Boolean,
        framingConfig: FramingConfig,
        maxIncomingMessageQueueSize: Int,
        incomingConnectionsThrottling: Option[IncomingConnectionThrottlingConfig],
        stalePeerDetectionConfig: Option[StalePeerDetectionConfig]
    ): Try[Config] = {
      val convertedKeyPair = CryptoUtils.convertBcToJceKeyPair(hostKeyPair)
      Config(
        bindAddress,
        keyType,
        convertedKeyPair,
        secureRandom,
        useNativeTlsImplementation,
        framingConfig,
        maxIncomingMessageQueueSize,
        incomingConnectionsThrottling,
        stalePeerDetectionConfig
      )
    }
  }

  /** Create the peer group as a resource that is guaranteed to initialize itself and shut itself down at the end. */
  def apply[M: Codec](config: Config)(implicit scheduler: Scheduler): Resource[Task, DynamicTLSPeerGroup[M]] =
    Resource.make {
      for {
        // Using MPMC because the channel creation event is only pushed after the SSL handshake,
        // which should take place on the channel thread, not the boss thread.
        queue <- CloseableQueue.unbounded[ServerEvent[PeerInfo, M]](ChannelType.MPMC)
        // NOTE: The DynamicTLSPeerGroup creates Netty workgroups in its constructor, so calling `shutdown` is a must.
        pg <- Task(new DynamicTLSPeerGroup[M](config, queue))
        // NOTE: In theory we wouldn't have to initialize a peer group (i.e. start listening to incoming events)
        // if all we wanted was to connect to remote clients, however to clean up we must call `shutdown` at which point
        // it will start and stop the server anyway, and the interface itself suggests that one can always start concuming
        // server events, so this is cleaner semantics.
        _ <- pg.initialize
      } yield pg
    }(_.shutdown)

}
