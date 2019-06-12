package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.cert.{Certificate, X509Certificate}

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco._
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.iohk.scalanet.peergroup.TLSPeerGroup._
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.bytes.ByteArrayEncoder
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import io.netty.handler.ssl.{SslContextBuilder, SslHandshakeCompletionEvent}
import io.netty.util
import monix.eval.Task
import monix.reactive.Observable
import monix.reactive.subjects.{PublishSubject, ReplaySubject, Subject}
import org.slf4j.LoggerFactory

import scala.concurrent.Promise
import scala.util.Success
import scala.collection.JavaConverters._

/**
  * PeerGroup implementation on top of TLS.
  * FIXME currently this class makes use of netty's LengthFieldPrepender to perform packet reassembly. This means
  * the encoded bytes provided by the callers codec are not identical to the bytes put on the wire (since a
  * length field is prepended to the byte stream). This class therefore cannot be used to talk to general services
  * that are not instances of TLSPeerGroup.
  *
  * @param config bind address etc. See the companion object.
  * @param codec a decco codec for reading writing messages to NIO ByteBuffer.
  * @tparam M the message type.
  */
class TLSPeerGroup[M](val config: Config)(implicit codec: Codec[M], bufferInstantiator: BufferInstantiator[ByteBuffer])
    extends TerminalPeerGroup[InetMultiAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  private[scalanet] class ServerChannelImpl[M](val nettyChannel: SocketChannel)(
      implicit codec: Codec[M]
  ) extends Channel[InetMultiAddress, M] {

    private val log = LoggerFactory.getLogger(getClass)
    private val messageSubject = ReplaySubject[M]()

    private val sslServerCtx = SslContextBuilder
      .forServer(config.certChainPrivateKey, config.certChain.asInstanceOf[List[X509Certificate]]: _*)
      .ciphers(TLSPeerGroup.supportedCipherSuites.asJava)
      .build()

    log.debug(
      s"Creating server channel from ${nettyChannel.localAddress()} to ${nettyChannel.remoteAddress()} with channel id ${nettyChannel.id}"
    )

    nettyChannel
      .pipeline()
      .addLast("ssl", sslServerCtx.newHandler(nettyChannel.alloc())) //This needs to be first
      .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
      .addLast("frameEncoder", new LengthFieldPrepender(4))
      .addLast(new MessageNotifier(messageSubject))

    override val to: InetMultiAddress = InetMultiAddress(nettyChannel.remoteAddress())

    override def sendMessage(message: M): Task[Unit] = {
      toTask(
        nettyChannel
          .writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)))
      )

    }

    override def in: Observable[M] = messageSubject

    override def close(): Task[Unit] = {
      messageSubject.onComplete()
      toTask(nettyChannel.close())
    }

  }

  private class ClientChannelImpl[M](inetSocketAddress: InetSocketAddress, clientBootstrap: Bootstrap)(
      implicit codec: Codec[M]
  ) extends Channel[InetMultiAddress, M] {

    private val log = LoggerFactory.getLogger(getClass)

    val to: InetMultiAddress = InetMultiAddress(inetSocketAddress)

    private val activation = Promise[ChannelHandlerContext]()
    private val activationF = activation.future

    private val deactivation = Promise[Unit]()
    private val deactivationF = deactivation.future

    private val messageSubject = ReplaySubject[M]()

    private val sslClientCtx = SslContextBuilder
      .forClient()
      .trustManager(config.trustStore.asInstanceOf[List[X509Certificate]]: _*)
      .build()

    private val bootstrap: Bootstrap = clientBootstrap
      .clone()
      .handler(new ChannelInitializer[SocketChannel]() {
        def initChannel(ch: SocketChannel): Unit = {
          val pipeline = ch.pipeline()
          val sslHandler = sslClientCtx.newHandler(ch.alloc())
          pipeline
            .addLast("ssl", sslHandler) //This needs to be first
            .addLast("frameEncoder", new LengthFieldPrepender(4))
            .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
            .addLast(new ByteArrayEncoder())
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
                    if (e.isSuccess) activation.success(ctx) else activation.failure(e.cause())

                  case _ =>
                    log.debug(
                      s"User Event client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
                    )
                }
                super.userEventTriggered(ctx, evt)
              }
            })
            .addLast(new MessageNotifier[M](messageSubject))
        }
      })

    def initialize: Task[ClientChannelImpl[M]] = {
      toTask(bootstrap.connect(inetSocketAddress)).map(_ => this)
    }

    override def sendMessage(message: M): Task[Unit] = {

      Task
        .fromFuture(activationF)
        .map(ctx => {
          log.debug(
            s"Processing outbound message from local address ${ctx.channel().localAddress()} " +
              s"to remote address ${ctx.channel().remoteAddress()} via channel id ${ctx.channel().id()}"
          )

          ctx.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)))
        })
        .map(_ => ())
    }

    override def in: Observable[M] = messageSubject

    override def close(): Task[Unit] = {
      messageSubject.onComplete()
      Task
        .fromFuture(activationF)
        .flatMap(ctx => toTask(ctx.close()))
        .flatMap(_ => Task.fromFuture(deactivationF))
    }
  }

  private val channelSubject = PublishSubject[Channel[InetMultiAddress, M]]()

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
        val newChannel = new ServerChannelImpl[M](ch)
        channelSubject.onNext(newChannel)
        log.debug(s"$processAddress received inbound from ${ch.remoteAddress()}.")
      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

  private val serverBind: ChannelFuture = serverBootstrap.bind(config.bindAddress)

  override def initialize(): Task[Unit] =
    toTask(serverBind).map(_ => log.info(s"Server bound to address ${config.bindAddress}"))

  override def processAddress: InetMultiAddress = config.processAddress

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = {
    new ClientChannelImpl[M](to.inetSocketAddress, clientBootstrap).initialize
  }

  override def server(): Observable[Channel[InetMultiAddress, M]] = channelSubject

  override def shutdown(): Task[Unit] = {
    channelSubject.onComplete()
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

  private class MessageNotifier[M](val messageSubject: Subject[M, M])(implicit codec: Codec[M])
      extends ChannelInboundHandlerAdapter {

    private val log = LoggerFactory.getLogger(getClass)

    override def channelInactive(channelHandlerContext: ChannelHandlerContext): Unit =
      messageSubject.onComplete()

    override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
      evt match {
        case e: SslHandshakeCompletionEvent =>
          log.debug(
            s"Ssl Handshake Server channel from ${ctx.channel().localAddress()} " +
              s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
          )
        case _ =>
          log.debug(
            s"User Event Server channel from ${ctx.channel().localAddress()} " +
              s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
          )
      }
      super.userEventTriggered(ctx, evt)
    }
    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {

      val messageE: Either[Codec.Failure, M] = codec.decode(msg.asInstanceOf[ByteBuf].nioBuffer().asReadOnlyBuffer())
      log.debug(
        s"Processing inbound message from remote address ${ctx.channel().remoteAddress()} " +
          s"to local address ${ctx.channel().localAddress()}, ${messageE.getOrElse("decode failed")}"
      )

      messageE.foreach { message =>
        messageSubject.onNext(message)
      }
    }
  }

  private def toTask(f: util.concurrent.Future[_]): Task[Unit] = {
    val promisedCompletion = Promise[Unit]()
    f.addListener((_: util.concurrent.Future[_]) => promisedCompletion.complete(Success(())))
    Task.fromFuture(promisedCompletion.future)
  }

}
