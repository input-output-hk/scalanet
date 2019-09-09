package io.iohk.scalanet.peergroup

import java.io.IOException
import java.net.{ConnectException, InetSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.security.PrivateKey
import java.security.cert.{Certificate, X509Certificate}

import io.iohk.decco._
import io.iohk.scalanet.codec.StreamCodec
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelBrokenException, HandshakeException, ServerEvent, TerminalPeerGroup}
import io.iohk.scalanet.peergroup.TLSPeerGroup._
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.ssl.{SslContext, SslContextBuilder, SslHandshakeCompletionEvent}
import javax.net.ssl.{SSLHandshakeException, SSLKeyException}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.observables.ConnectableObservable
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.util.control.NonFatal

/**
  * PeerGroup implementation on top of TLS.
  * FIXME currently this class makes use of netty's LengthFieldPrepender to perform packet reassembly. This means
  * the encoded bytes provided by the callers codec are not identical to the bytes put on the wire (since a
  * length field is prepended to the byte stream). This class therefore cannot be used to talk to general services
  * that are not instances of TLSPeerGroup.
  *
  * @param config bind address etc. See the companion object.
  * @param codec  a decco codec for reading writing messages to NIO ByteBuffer.
  * @tparam M the message type.
  */
class TLSPeerGroup[M](val config: Config)(
    implicit codec: StreamCodec[M],
    bi: BufferInstantiator[ByteBuffer],
    scheduler: Scheduler
) extends TerminalPeerGroup[InetMultiAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val sslClientCtx: SslContext = SslContextBuilder
    .forClient()
    .trustManager(config.trustStore.asInstanceOf[List[X509Certificate]]: _*)
    .build()

  private val sslServerCtx: SslContext = SslContextBuilder
    .forServer(config.certChainPrivateKey, config.certChain.asInstanceOf[List[X509Certificate]]: _*)
    .ciphers(TLSPeerGroup.supportedCipherSuites.asJava)
    .build()

  private val serverSubject = ConnectableSubject[ServerEvent[InetMultiAddress, M]]()
  private val workerGroup = new NioEventLoopGroup()

  private val clientBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .option[Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)

  private val serverBootstrap = new ServerBootstrap()
    .group(workerGroup)
    .channel(classOf[NioServerSocketChannel])
    .childHandler(new ChannelInitializer[SocketChannel]() {
      override def initChannel(ch: SocketChannel): Unit = {
        val newChannel = new ServerChannelImpl[M](serverSubject, ch, sslServerCtx, codec, bi)
        serverSubject.onNext(ChannelCreated(newChannel))
        log.debug(s"$processAddress received inbound from ${ch.remoteAddress()}.")
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

  override def processAddress: InetMultiAddress = config.processAddress

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = {
    new ClientChannelImpl[M](to.inetSocketAddress, clientBootstrap, sslClientCtx, codec, bi).initialize
  }

  override def server(): ConnectableObservable[ServerEvent[InetMultiAddress, M]] = serverSubject

  override def shutdown(): Task[Unit] = {
    serverSubject.onComplete()
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }
}

object TLSPeerGroup {

  case class Config(
      bindAddress: InetSocketAddress,
      processAddress: InetMultiAddress,
      certChainPrivateKey: PrivateKey,
      certChain: List[Certificate],
      trustStore: List[Certificate],
      clientAuthRequired: Boolean
  )

  object Config {
    def apply(
        bindAddress: InetSocketAddress,
        certChainPrivateKey: PrivateKey,
        certChain: List[Certificate],
        trustStore: List[Certificate]
    ): Config =
      Config(
        bindAddress,
        InetMultiAddress(bindAddress),
        certChainPrivateKey,
        certChain,
        trustStore = trustStore,
        clientAuthRequired = false
      )

    def apply(
        bindAddress: InetSocketAddress,
        certChainPrivateKey: PrivateKey,
        certChain: List[Certificate],
        trustStore: List[Certificate],
        clientAuthRequired: Boolean = true
    ): Config =
      Config(bindAddress, InetMultiAddress(bindAddress), certChainPrivateKey, certChain, trustStore, clientAuthRequired)

  }

  val supportedCipherSuites: Seq[String] = Seq(
    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
    "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
    "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA",
    "TLS_ECDHE_RSA_WITH_RC4_128_SHA",
    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA",
    "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA"
  )

  private class MessageNotifier[M](
      val messageSubject: ConnectableSubject[M],
      codec: StreamCodec[M],
      bi: BufferInstantiator[ByteBuffer]
  ) extends ChannelInboundHandlerAdapter {

    private val log = LoggerFactory.getLogger(getClass)

    override def channelInactive(channelHandlerContext: ChannelHandlerContext): Unit =
      messageSubject.onComplete()

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val byteBuf = msg.asInstanceOf[ByteBuf]
      try {
        log.debug(
          s"Processing inbound message from remote address ${ctx.channel().remoteAddress()} " +
            s"to local address ${ctx.channel().localAddress()}"
        )
        codec
          .streamDecode(byteBuf.nioBuffer().asReadOnlyBuffer())(bi)
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
      inetSocketAddress: InetSocketAddress,
      clientBootstrap: Bootstrap,
      sslClientCtx: SslContext,
      codec: StreamCodec[M],
      bi: BufferInstantiator[ByteBuffer]
  )(implicit scheduler: Scheduler)
      extends Channel[InetMultiAddress, M] {

    private val log = LoggerFactory.getLogger(getClass)

    val to: InetMultiAddress = InetMultiAddress(inetSocketAddress)

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
                    log.debug(
                      s"Ssl Handshake client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
                    )
                    if (e.isSuccess)
                      activation.success(ctx)
                    else {
                      activation.failure(e.cause())
                    }

                  case _ =>
                    log.debug(
                      s"User Event client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
                    )
                }
              }
            })
            .addLast(new MessageNotifier[M](messageSubject, codec, bi))
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
      case _ =>
        t
    }

    def initialize: Task[ClientChannelImpl[M]] = {
      toTask(bootstrap.connect(inetSocketAddress))
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
          toTask(ctx.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)(bi))))
        })
        .onErrorRecoverWith {
          case e: IOException =>
            Task.raiseError(new ChannelBrokenException[InetMultiAddress](to, e))
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

  private[scalanet] class ServerChannelImpl[M](
      serverSubject: ConnectableSubject[ServerEvent[InetMultiAddress, M]],
      val nettyChannel: SocketChannel,
      sslServerCtx: SslContext,
      codec: StreamCodec[M],
      bi: BufferInstantiator[ByteBuffer]
  )(implicit scheduler: Scheduler)
      extends Channel[InetMultiAddress, M] {

    private val log = LoggerFactory.getLogger(getClass)
    val messageSubject = ConnectableSubject[M]()

    log.debug(
      s"Creating server channel from ${nettyChannel.localAddress()} to ${nettyChannel.remoteAddress()} with channel id ${nettyChannel.id}"
    )

    nettyChannel
      .pipeline()
      .addLast("ssl", sslServerCtx.newHandler(nettyChannel.alloc())) //This needs to be first
      .addLast(new ChannelInboundHandlerAdapter() {
        override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
          evt match {
            case e: SslHandshakeCompletionEvent =>
              val localAddress = InetMultiAddress(ctx.channel().localAddress().asInstanceOf[InetSocketAddress])
              val remoteAddress = InetMultiAddress(ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress])
              log.debug(
                s"Ssl Handshake server channel from $localAddress " +
                  s"to $remoteAddress with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
              )
              if (!e.isSuccess)
                serverSubject
                  .onNext(PeerGroup.ServerEvent.HandshakeFailed(new HandshakeException(remoteAddress, e.cause())))
          }
        }
      })
      .addLast(new MessageNotifier(messageSubject, codec, bi))

    override val to: InetMultiAddress = InetMultiAddress(nettyChannel.remoteAddress())

    override def sendMessage(message: M): Task[Unit] = {
      toTask(nettyChannel.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)(bi))))
        .onErrorRecoverWith {
          case e: ClosedChannelException =>
            Task.raiseError(new ChannelBrokenException[InetMultiAddress](to, e))
        }
    }

    override def in: ConnectableObservable[M] = messageSubject

    override def close(): Task[Unit] = {
      messageSubject.onComplete()
      toTask(nettyChannel.close())
    }
  }

}
