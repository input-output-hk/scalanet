package io.iohk.scalanet.peergroup.dynamictls

import java.io.IOException
import java.net.{ConnectException, InetSocketAddress}
import java.nio.channels.ClosedChannelException
import java.security._
import java.security.cert.X509Certificate

import io.iohk.scalanet.codec.StreamCodec
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.crypto.CryptoUtils.Secp256r1
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelBrokenException, HandshakeException, ServerEvent, TerminalPeerGroup}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.SignedKeyExtensionNodeData
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.{
  ClientChannelImpl,
  Config,
  PeerInfo,
  ServerChannelBuilder
}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroupUtils.{SSLContextForClient, SSLContextForSever}
import io.iohk.scalanet.peergroup.{Addressable, Channel, InetMultiAddress, PeerGroup}
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import io.netty.handler.ssl.{SslContext, SslHandshakeCompletionEvent}
import javax.net.ssl._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.observables.ConnectableObservable
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.slf4j.LoggerFactory
import scodec.bits.BitVector

import scala.collection.JavaConverters._
import scala.concurrent.Promise
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
class DynamicTLSPeerGroup[M](val config: Config)(
    implicit codec: StreamCodec[M],
    scheduler: Scheduler
) extends TerminalPeerGroup[PeerInfo, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val sslServerCtx: SslContext = DynamicTLSPeerGroupUtils.buildCustomSSlContext(SSLContextForSever, config)

  private val serverSubject = ConnectableSubject[ServerEvent[PeerInfo, M]]()

  private val workerGroup = new NioEventLoopGroup()

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
        new ServerChannelBuilder[M](serverSubject, ch, sslServerCtx, codec)
        log.info(s"$processAddress received inbound from ${ch.remoteAddress()}.")
      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

  private lazy val serverBind: ChannelFuture = serverBootstrap.bind(config.bindAddress)

  override def initialize(): Task[Unit] =
    toTask(serverBind).map(_ => log.info(s"Server bound to address ${config.bindAddress}")).onErrorRecoverWith {
      case NonFatal(e) => Task.raiseError(InitializationError(e.getMessage, e.getCause))
    }

  override def processAddress: PeerInfo = config.peerInfo

  override def client(to: PeerInfo): Task[Channel[PeerInfo, M]] = {
    // Creating new ssl context for each client is necessary, as this is only reliable way to pass peerInfo to TrustManager
    // which takes care of validating certificates and server node id.
    // Using Netty SSLEngine.getSession.putValue does not work as expected as until successfulhandshake there is no separate
    // session for each connection.
    new ClientChannelImpl[M](
      to,
      clientBootstrap,
      DynamicTLSPeerGroupUtils.buildCustomSSlContext(SSLContextForClient(to), config),
      codec
    ).initialize
  }

  override def server(): ConnectableObservable[ServerEvent[PeerInfo, M]] = serverSubject

  override def shutdown(): Task[Unit] = {
    serverSubject.onComplete()
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }
}

object DynamicTLSPeerGroup {
  case class PeerInfo(id: BitVector, address: InetMultiAddress)
  object PeerInfo {
    implicit val peerInInst = new Addressable[PeerInfo] {
      override def getAddress(a: PeerInfo): InetSocketAddress = a.address.inetSocketAddress
    }
  }

  case class Config(
      bindAddress: InetSocketAddress,
      peerInfo: PeerInfo,
      connectionKeyPair: KeyPair,
      connectionCertificate: X509Certificate
  )

  object Config {
    // FIXME: For now we support only Secp256 keys in ethereum format
    def apply(
        bindAddress: InetSocketAddress,
        keyType: KeyType,
        hostKeyPair: KeyPair,
        secureRandom: SecureRandom
    ): Try[Config] = {

      SignedKeyExtensionNodeData(keyType, hostKeyPair, Secp256r1, secureRandom).map { nodeData =>
        Config(
          bindAddress,
          PeerInfo(nodeData.calculatedNodeId, InetMultiAddress(bindAddress)),
          nodeData.generatedConnectionKey,
          nodeData.certWithExtension
        )
      }
    }

    def apply(
        bindAddress: InetSocketAddress,
        keyType: KeyType,
        hostKeyPair: AsymmetricCipherKeyPair,
        secureRandom: SecureRandom
    ): Try[Config] = {
      val convertedKeyPair = CryptoUtils.convertBcToJceKeyPair(hostKeyPair)
      Config(bindAddress, keyType, convertedKeyPair, secureRandom)
    }
  }

  private class MessageNotifier[M](
      val messageSubject: ConnectableSubject[M],
      codec: StreamCodec[M]
  ) extends ChannelInboundHandlerAdapter {

    private val log = LoggerFactory.getLogger(getClass)

    override def channelInactive(channelHandlerContext: ChannelHandlerContext): Unit =
      messageSubject.onComplete()

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val byteBuf = msg.asInstanceOf[ByteBuf]
      try {
        log.info(
          s"Processing inbound message from remote address ${ctx.channel().remoteAddress()} " +
            s"to local address ${ctx.channel().localAddress()}"
        )
        codec
          .streamDecode(BitVector(byteBuf.nioBuffer()))
          .foreach(message => messageSubject.onNext(message))
      } finally {
        byteBuf.release()
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      // swallow netty's default logging of the stack trace.
    }
  }

  private class ClientChannelImpl[M](
      peerInfo: PeerInfo,
      clientBootstrap: Bootstrap,
      sslClientCtx: SslContext,
      codec: StreamCodec[M]
  )(implicit scheduler: Scheduler)
      extends Channel[PeerInfo, M] {

    private val log = LoggerFactory.getLogger(getClass)

    val to: PeerInfo = peerInfo

    private val activation = Promise[ChannelHandlerContext]()
    private val activationF = activation.future

    private val deactivation = Promise[Unit]()
    private val deactivationF = deactivation.future

    private val messageSubject = ConnectableSubject[M]()

    private val bootstrap: Bootstrap = clientBootstrap
      .clone()
      .handler(new ChannelInitializer[SocketChannel]() {
        def initChannel(ch: SocketChannel): Unit = {
          val pipeline = ch.pipeline()
          val sslHandler = sslClientCtx.newHandler(ch.alloc())

          pipeline
            .addLast("ssl", sslHandler) //This needs to be first
            .addLast(new ChannelInboundHandlerAdapter() {

              override def channelInactive(ctx: ChannelHandlerContext): Unit = {
                deactivation.success(())
              }

              override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
                evt match {
                  case e: SslHandshakeCompletionEvent =>
                    log.info(
                      s"Ssl Handshake client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
                    )
                    if (e.isSuccess)
                      activation.success(ctx)
                    else {
                      activation.failure(e.cause())
                    }

                  case _ =>
                    log.info(
                      s"User Event client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
                    )
                }
              }
            })
            .addLast(new MessageNotifier[M](messageSubject, codec))
        }
      })

    private def mapException(t: Throwable): Throwable = t match {
      case _: ClosedChannelException =>
        new PeerGroup.ChannelBrokenException(to, t)
      case _: ConnectException =>
        new PeerGroup.ChannelSetupException(to, t)
      case _: SSLKeyException =>
        new PeerGroup.HandshakeException(to, t)
      case _: SSLHandshakeException =>
        new PeerGroup.HandshakeException(to, t)
      case _: SSLException =>
        new PeerGroup.HandshakeException(to, t)
      case _ =>
        t
    }

    def initialize: Task[ClientChannelImpl[M]] = {
      toTask(bootstrap.connect(peerInfo.address.inetSocketAddress))
        .flatMap(_ => Task.fromFuture(activationF))
        .map(_ => this)
        .onErrorRecoverWith {
          case t: Throwable =>
            Task.raiseError(mapException(t))
        }
    }

    override def sendMessage(message: M): Task[Unit] = {
      Task
        .fromFuture(activationF)
        .flatMap(ctx => {
          log.debug(
            s"Processing outbound message from local address ${ctx.channel().localAddress()} " +
              s"to remote address ${ctx.channel().remoteAddress()} via channel id ${ctx.channel().id()}"
          )

          Task.fromTry(codec.encode(message).toTry).flatMap { enc =>
            toTask(ctx.writeAndFlush(Unpooled.wrappedBuffer(enc.toByteBuffer)))
          }
        })
        .onErrorRecoverWith {
          case e: IOException =>
            Task.raiseError(new ChannelBrokenException[PeerInfo](to, e))
        }
        .map(_ => ())
    }

    override def in: ConnectableObservable[M] = messageSubject

    override def close(): Task[Unit] = {
      messageSubject.onComplete()
      Task
        .fromFuture(activationF)
        .flatMap(ctx => toTask(ctx.close()))
        .flatMap(_ => Task.fromFuture(deactivationF))
    }
  }

  private[scalanet] class ServerChannelBuilder[M](
      serverSubject: ConnectableSubject[ServerEvent[PeerInfo, M]],
      val nettyChannel: SocketChannel,
      sslServerCtx: SslContext,
      codec: StreamCodec[M]
  )(implicit scheduler: Scheduler) {
    private val log = LoggerFactory.getLogger(getClass)
    val sslHandler = sslServerCtx.newHandler(nettyChannel.alloc())

    val messageSubject = ConnectableSubject[M]()
    val sslEngine = sslHandler.engine()

    nettyChannel
      .pipeline()
      .addLast("ssl", sslHandler) //This needs to be first
      .addLast(new ChannelInboundHandlerAdapter() {
        override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
          evt match {
            case e: SslHandshakeCompletionEvent =>
              val localAddress = InetMultiAddress(ctx.channel().localAddress().asInstanceOf[InetSocketAddress])
              val remoteAddress = InetMultiAddress(ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress])
              if (e.isSuccess) {
                // Hacky way of getting id of incoming peer form ssl engine. Only works becouse we have new engine for
                // each connection
                val params = sslEngine.getSSLParameters.getServerNames.asScala.head
                val peerId = BitVector(params.getEncoded)
                log.debug(
                  s"Ssl Handshake server channel from $localAddress " +
                    s"to $remoteAddress with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
                )
                val channel = new ServerChannelImpl[M](nettyChannel, peerId, codec, messageSubject)
                serverSubject.onNext(ChannelCreated(channel))
              } else {
                // Handshake failed we do not habe id of remote peer
                serverSubject
                  .onNext(
                    PeerGroup.ServerEvent
                      .HandshakeFailed(new HandshakeException(PeerInfo(BitVector.empty, remoteAddress), e.cause()))
                  )
              }
          }
        }
      })
      .addLast(new MessageNotifier(messageSubject, codec))
  }

  private[scalanet] class ServerChannelImpl[M](
      val nettyChannel: SocketChannel,
      peerId: BitVector,
      codec: StreamCodec[M],
      messageSubject: ConnectableSubject[M]
  )(implicit scheduler: Scheduler)
      extends Channel[PeerInfo, M] {

    private val log = LoggerFactory.getLogger(getClass)

    log.debug(
      s"Creating server channel from ${nettyChannel.localAddress()} to ${nettyChannel.remoteAddress()} with channel id ${nettyChannel.id}"
    )

    override val to: PeerInfo = PeerInfo(peerId, InetMultiAddress(nettyChannel.remoteAddress()))

    override def sendMessage(message: M): Task[Unit] = {
      Task.fromTry(codec.encode(message).toTry).flatMap { enc =>
        toTask(nettyChannel.writeAndFlush(Unpooled.wrappedBuffer(enc.toByteBuffer)))
          .onErrorRecoverWith {
            case e: IOException =>
              Task.raiseError(new ChannelBrokenException[PeerInfo](to, e))
          }
      }
    }

    override def in: ConnectableObservable[M] = messageSubject

    override def close(): Task[Unit] = {
      messageSubject.onComplete()
      toTask(nettyChannel.close())
    }
  }

}
