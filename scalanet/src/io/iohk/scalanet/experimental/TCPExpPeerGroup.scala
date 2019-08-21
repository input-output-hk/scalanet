package io.iohk.scalanet.experimental

import java.io.IOException
import java.net.{ConnectException, InetSocketAddress}
import java.nio.ByteBuffer

import io.iohk.decco.BufferInstantiator
import io.iohk.scalanet.codec.StreamCodec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelBrokenException, ChannelSetupException}
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import monix.eval.Task
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.Promise
import scala.util.control.NonFatal

class TCPExpPeerGroup[M](address: InetSocketAddress)(
    implicit
    codec: StreamCodec[M],
    bi: BufferInstantiator[ByteBuffer]
) extends EPeerGroup[InetSocketAddress, M] {

  private val log = LoggerFactory.getLogger(getClass)

  private val workerGroup = new NioEventLoopGroup()

  private val messageHandlers = createSet[Envelope[InetSocketAddress, M] => Unit]

  private val connectionHandlers = createSet[EConnection[M] => Unit]

  private val clientBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)

  private val serverBootstrap = new ServerBootstrap()
    .group(workerGroup)
    .channel(classOf[NioServerSocketChannel])
    .childHandler(new ChannelInitializer[SocketChannel]() {
      override def initChannel(nettyChannel: SocketChannel): Unit = {
        for (h <- connectionHandlers) h(new TCPEConnection[M](nettyChannel, codec, bi))
        nettyChannel
          .pipeline()
          .addLast(new ChannelInboundHandlerAdapter {
            override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
              val byteBuf = msg.asInstanceOf[ByteBuf]
              try {
                log.debug(
                  s"Processing inbound message from remote address ${ctx.channel().remoteAddress()} " +
                    s"to local address ${ctx.channel().localAddress()}"
                )
                for {
                  message <- codec.streamDecode(byteBuf.nioBuffer())(bi)
                  h <- messageHandlers
                  ch = ctx.channel().asInstanceOf[SocketChannel]
                } h(
                  Envelope(new TCPEServerChannel[M](ch, codec, bi), ch.remoteAddress(), message)
                )
              } finally {
                byteBuf.release()
              }
            }
          })
      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

  private lazy val serverBind: ChannelFuture = serverBootstrap.bind(address)

  override def processAddress: InetSocketAddress = address

  override def connect(): Task[Unit] =
    toTask(serverBind).map(_ => log.info(s"Server bound to address $address")).onErrorRecoverWith {
      case NonFatal(e) => Task.raiseError(InitializationError(e.getMessage, e.getCause))
    }

  override def client(to: InetSocketAddress): Task[EChannel[InetSocketAddress, M]] = {
    new TCPExpChannel[M](messageHandlers, clientBootstrap.clone(), codec, bi, to).initialize
  }

  override def onConnectionArrival(connectionHandler: EConnection[M] => Unit): Unit = {
    connectionHandlers += connectionHandler
    log.info(
      s"Connection's handled registered by $processAddress.\nThere are ${connectionHandlers.size} handlers for the next connection"
    )
  }

  override def onMessageReception(handler: Envelope[InetSocketAddress, M] => Unit): Unit = {
    messageHandlers += handler
    log.info(
      s"Meesage's handler registered by $processAddress.\nThere are ${messageHandlers.size} handlers for the next message"
    )
  }

  override def shutdown(): Task[Unit] = {
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }
}

class TCPExpChannel[M](
    handlers: mutable.Set[Envelope[InetSocketAddress, M] => Unit],
    clientBootstrap: Bootstrap,
    codec: StreamCodec[M],
    bi: BufferInstantiator[ByteBuffer],
    remoteAddress: InetSocketAddress
) extends EChannel[InetSocketAddress, M] {

  private val activation = Promise[ChannelHandlerContext]()
  private val activationF = activation.future
  private val deactivation = Promise[Unit]()
  private val deactivationF = deactivation.future

  clientBootstrap
    .handler(new ChannelInitializer[SocketChannel]() {
      def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline()
          .addLast(new ChannelInboundHandlerAdapter() {
            override def channelActive(ctx: ChannelHandlerContext): Unit = {
              log.debug(
                s"Creating client channel from ${ctx.channel().localAddress()} " +
                  s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
              )
              activation.success(ctx)
            }

            override def channelInactive(ctx: ChannelHandlerContext): Unit = {
              deactivation.success(())
            }
          })
          .addLast(new ChannelInboundHandlerAdapter {
            override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
              val byteBuf = msg.asInstanceOf[ByteBuf]
              try {
                log.debug(
                  s"Processing inbound message from remote address ${ctx.channel().remoteAddress()} " +
                    s"to local address ${ctx.channel().localAddress()}"
                )
                for {
                  message <- codec.streamDecode(byteBuf.nioBuffer())(bi)
                  h <- handlers
                  ch = ctx.channel().asInstanceOf[SocketChannel]
                } h(
                  Envelope(new TCPEServerChannel[M](ch, codec, bi), ch.remoteAddress(), message)
                )
              } finally {
                byteBuf.release()
              }
            }
          })
      }
    })

  def initialize: Task[TCPExpChannel[M]] = {
    toTask(clientBootstrap.connect(remoteAddress))
      .onErrorRecoverWith {
        case e: ConnectException =>
          Task.raiseError(new ChannelSetupException[InetSocketAddress](to, e))
      }
      .map(_ => this)
  }

  private val log = LoggerFactory.getLogger(getClass)

  override def to: InetSocketAddress = remoteAddress

  override def sendMessage(m: M): Task[Unit] = {
    Task
      .fromFuture(activationF)
      .flatMap { ctx =>
        log.debug(s"Sending $m to $remoteAddress")
        toTask { ctx.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(m)(bi))) }
      } onErrorRecoverWith {
      case e: IOException =>
        Task.raiseError(new ChannelBrokenException[InetSocketAddress](to, e))
    }
  }

  override def close(): Task[Unit] =
    Task
      .fromFuture(activationF)
      .flatMap(ctx => toTask(ctx.close()))
      .flatMap(_ => Task.fromFuture(deactivationF))
}

class TCPEServerChannel[M](
    nettyChannel: SocketChannel,
    codec: StreamCodec[M],
    bi: BufferInstantiator[ByteBuffer]
) extends EChannel[InetSocketAddress, M] {
  override def to: InetSocketAddress = nettyChannel.remoteAddress()

  override def sendMessage(m: M): Task[Unit] =
    toTask(nettyChannel.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(m)(bi))))
      .onErrorRecoverWith {
        case e: IOException =>
          Task.raiseError(new ChannelBrokenException[InetSocketAddress](nettyChannel.remoteAddress(), e))
      }

  override def close(): Task[Unit] = toTask(nettyChannel.close())

}

class TCPEConnection[M](
    nettyChannel: SocketChannel,
    codec: StreamCodec[M],
    bi: BufferInstantiator[ByteBuffer]
) extends EConnection[M] {

  override def underlyingAddress: InetSocketAddress = nettyChannel.remoteAddress()

  override def replyWith(m: M): Task[Unit] =
    toTask(nettyChannel.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(m)(bi))))
      .onErrorRecoverWith {
        case e: IOException =>
          Task.raiseError(new ChannelBrokenException[InetSocketAddress](nettyChannel.remoteAddress(), e))
      }

  override def close(): Task[Unit] = toTask(nettyChannel.close())
}
