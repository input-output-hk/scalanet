package io.iohk.scalanet.experimental.observables
import java.io.IOException
import java.net.{ConnectException, InetSocketAddress}
import java.nio.ByteBuffer

import io.iohk.decco.BufferInstantiator
import io.iohk.scalanet.codec.StreamCodec
import io.iohk.scalanet.experimental._
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
import monix.execution.Scheduler
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable, Observer}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.Promise
import scala.util.control.NonFatal

class ObsTCPPeerGroup[M](address: InetSocketAddress)(
    implicit
    codec: StreamCodec[M],
    bi: BufferInstantiator[ByteBuffer],
    scheduler: Scheduler
) extends ObsPeerGroup[InetSocketAddress, M] {

  private val log = LoggerFactory.getLogger(getClass)

  private val workerGroup = new NioEventLoopGroup()

  private val connectionsSubject = ConcurrentSubject[ObsConnection[M]](MulticastStrategy.publish)
  private val messageHandler = createSet[Observer[ObsEnvelope[InetSocketAddress, M]]]

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
        connectionsSubject.onNext(TCPObsConnection(nettyChannel, codec, bi))
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
                  o <- messageHandler
                  ch = ctx.channel().asInstanceOf[SocketChannel]
                } o.onNext(ObsEnvelope(TCPObsServerChannel(ch, codec, bi), ch.remoteAddress(), message))
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

  override def client(to: InetSocketAddress): Task[ObsChannel[M]] = {
    TCPObsClientChannel[M](messageHandler, clientBootstrap, codec.cleanSlate, bi, to).initialize
  }

  override def incomingConnections(): Observable[ObsConnection[M]] = connectionsSubject

  override def onMessageReception(feedTo: Observer[ObsEnvelope[InetSocketAddress, M]]): Unit = {
    if (messageHandler.isEmpty) {
      messageHandler += feedTo
    }
    log.info(s"Handler registered by $processAddress")
  }

  override def shutdown(): Task[Unit] = {
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }
}

case class TCPObsClientChannel[M](
    handler: mutable.Set[Observer[ObsEnvelope[InetSocketAddress, M]]],
    clientBootstrap: Bootstrap,
    codec: StreamCodec[M],
    bi: BufferInstantiator[ByteBuffer],
    remoteAddress: InetSocketAddress
) extends ObsChannel[M] { channelSelf =>

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
                  o <- handler
                } o.onNext(ObsEnvelope(channelSelf, remoteAddress, message))
              } finally {
                byteBuf.release()
              }
            }
          })
      }
    })

  def initialize: Task[ObsChannel[M]] = {
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

case class TCPObsServerChannel[M](
    nettyChannel: SocketChannel,
    codec: StreamCodec[M],
    bi: BufferInstantiator[ByteBuffer]
) extends ObsChannel[M] {

  override def to: InetSocketAddress = nettyChannel.remoteAddress()

  override def sendMessage(m: M): Task[Unit] =
    toTask(nettyChannel.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(m)(bi))))
      .onErrorRecoverWith {
        case e: IOException =>
          Task.raiseError(new ChannelBrokenException[InetSocketAddress](nettyChannel.remoteAddress(), e))
      }

  override def close(): Task[Unit] = toTask(nettyChannel.close())
}

case class TCPObsConnection[M](
    nettyChannel: SocketChannel,
    codec: StreamCodec[M],
    bi: BufferInstantiator[ByteBuffer]
) extends ObsConnection[M] {

  override def from: InetSocketAddress = nettyChannel.remoteAddress()

  override def sendMessage(m: M): Task[Unit] =
    toTask(nettyChannel.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(m)(bi))))
      .onErrorRecoverWith {
        case e: IOException =>
          Task.raiseError(new ChannelBrokenException[InetSocketAddress](nettyChannel.remoteAddress(), e))
      }

  override def close(): Task[Unit] = toTask(nettyChannel.close())
}
