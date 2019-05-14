package io.iohk.scalanet.peergroup

import java.net.{InetAddress, InetSocketAddress}

import io.iohk.decco.{Codec, DecodeFailure}
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.iohk.scalanet.peergroup.TCPPeerGroup._
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import io.netty.handler.codec.bytes.ByteArrayEncoder
import io.netty.util
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, Promise}
import scala.util.Success

class TCPPeerGroup[M](val config: Config)(implicit scheduler: Scheduler, codec: Codec[M])
    extends TerminalPeerGroup[InetMultiAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val channelSubscribers =
    new Subscribers[Channel[InetMultiAddress, M]](s"Channel Subscribers for TCPPeerGroup@'$processAddress'")

  private val workerGroup = new NioEventLoopGroup()

  private val clientBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)

  private val serverBootstrap = new ServerBootstrap()
    .group(workerGroup)
    .channel(classOf[NioServerSocketChannel])
    .childHandler(new ChannelInitializer[SocketChannel]() {
      override def initChannel(ch: SocketChannel): Unit = {
        val newChannel = new ServerChannelImpl[M](ch)
        channelSubscribers.notify(newChannel)
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

  override def server(): Observable[Channel[InetMultiAddress, M]] = channelSubscribers.messageStream

  override def shutdown(): Task[Unit] = {
    channelSubscribers.onComplete()
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }
}

object TCPPeerGroup {
  case class Config(
                     bindAddress: InetSocketAddress,
                     processAddress: InetMultiAddress,
                     remoteHostConfig: Map[InetAddress, Int] = Map.empty[InetAddress, Int]
  )

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, new InetMultiAddress(bindAddress))
  }

  private[scalanet] class ServerChannelImpl[M](val nettyChannel: SocketChannel)(implicit codec: Codec[M], scheduler: Scheduler)
    extends Channel[InetMultiAddress, M] {

    private val log = LoggerFactory.getLogger(getClass)

    log.debug(s"Creating server channel from ${nettyChannel.localAddress()} to ${nettyChannel.remoteAddress()} with channel id ${nettyChannel.id}")

    private val messageSubscribers = new Subscribers[M](s"Subscribers for ServerChannelImpl@${nettyChannel.id}")

    nettyChannel
      .pipeline()
      .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
      .addLast("frameEncoder", new LengthFieldPrepender(4))
      .addLast(new MessageNotifier(messageSubscribers))

    override val to: InetMultiAddress = InetMultiAddress(nettyChannel.remoteAddress())

    override def sendMessage(message: M): Task[Unit] = {
      toTask({
        nettyChannel
          .writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)))
      })
    }

    override def in: Observable[M] = messageSubscribers.messageStream

    override def close(): Task[Unit] = {
      messageSubscribers.onComplete()
      toTask(nettyChannel.close())
    }
  }

  private class ClientChannelImpl[M](inetSocketAddress: InetSocketAddress, clientBootstrap: Bootstrap)(implicit codec: Codec[M], scheduler: Scheduler)
    extends Channel[InetMultiAddress, M] {

    private val log = LoggerFactory.getLogger(getClass)

    val to: InetMultiAddress = InetMultiAddress(inetSocketAddress)

    private val activation = Promise[ChannelHandlerContext]()
    private val activationF = activation.future
    private val deactivation = Promise[Unit]()
    private val deactivationF = deactivation.future

    private val subscribers = new Subscribers[M]

    private val bootstrap: Bootstrap = clientBootstrap
      .clone()
      .handler(new ChannelInitializer[SocketChannel]() {
        def initChannel(ch: SocketChannel): Unit = {
          ch.pipeline()
            .addLast("frameEncoder", new LengthFieldPrepender(4))
            .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
            .addLast(new ByteArrayEncoder())
            .addLast(new ChannelInboundHandlerAdapter() {
              override def channelActive(ctx: ChannelHandlerContext): Unit = {
                log.debug(s"Creating client channel from ${ctx.channel().localAddress()} " +
                  s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}")
                activation.complete(Success(ctx))
              }

              override def channelInactive(ctx: ChannelHandlerContext): Unit = {
                deactivation.complete(Success(()))
              }
            })
            .addLast(new MessageNotifier[M](subscribers))
        }
      })

    def initialize: Task[ClientChannelImpl[M]] = toTask(bootstrap.connect(inetSocketAddress)).map(_ => this)

    override def sendMessage(message: M): Task[Unit] = {

      val f: Future[Unit] =
        activationF
          .map(ctx => {
            log.debug(
              s"Processing outbound message from local address ${ctx.channel().localAddress()} " +
                s"to remote address ${ctx.channel().remoteAddress()} via channel id ${ctx.channel().id()}"
            )
            ctx.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)))
          })
          .map(_ => ())

      Task.fromFuture(f)
    }

    override def in: Observable[M] = subscribers.messageStream

    override def close(): Task[Unit] = {
      subscribers.onComplete()
      activationF.foreach(ctx => ctx.close())
      Task.fromFuture(deactivationF)
    }
  }

  private class MessageNotifier[M](val messageSubscribers: Subscribers[M])(implicit codec: Codec[M]) extends ChannelInboundHandlerAdapter {

    private val log = LoggerFactory.getLogger(getClass)

    override def channelInactive(channelHandlerContext: ChannelHandlerContext): Unit =
      messageSubscribers.onComplete()

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val messageE: Either[DecodeFailure, M] = codec.decode(msg.asInstanceOf[ByteBuf].nioBuffer().asReadOnlyBuffer())
      log.debug(
        s"Processing inbound message from remote address ${ctx.channel().remoteAddress()} " +
          s"to local address ${ctx.channel().localAddress()}, ${messageE.getOrElse("decode failed")}"
      )

      messageE.foreach { message =>
        messageSubscribers.notify(message)
      }
    }
  }

  private def toTask(f: util.concurrent.Future[_]): Task[Unit] = {
    val promisedCompletion = Promise[Unit]()
    f.addListener((_: util.concurrent.Future[_]) => promisedCompletion.complete(Success(())))
    Task.fromFuture(promisedCompletion.future)
  }
}
