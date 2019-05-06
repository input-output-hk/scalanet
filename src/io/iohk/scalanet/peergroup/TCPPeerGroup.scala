package io.iohk.scalanet.peergroup

import java.net.{InetAddress, InetSocketAddress}

import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.iohk.scalanet.peergroup.TCPPeerGroup.Config
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
    extends TerminalPeerGroup[InetSocketAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val channelSubscribers =
    new Subscribers[Channel[InetSocketAddress, M]](s"Channel Subscribers for TCPPeerGroup@'$processAddress'")

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
        val newChannel = new ServerChannelImpl(ch)
        channelSubscribers.notify(newChannel)
      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

  private val serverBind: ChannelFuture = serverBootstrap.bind(config.bindAddress)

  override def initialize(): Task[Unit] =
    toTask(serverBind).map(_ => log.info(s"Server bound to address ${config.bindAddress}"))

  override def processAddress: InetSocketAddress = config.processAddress

  override def client(to: InetSocketAddress): Task[Channel[InetSocketAddress, M]] = {
    new ClientChannelImpl(to).initialize
  }

  override def server(): Observable[Channel[InetSocketAddress, M]] = channelSubscribers.messageStream

  override def shutdown(): Task[Unit] =
    for {

      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()

  private def toTask(f: util.concurrent.Future[_]): Task[Unit] = {
    val promisedCompletion = Promise[Unit]()
    f.addListener((_: util.concurrent.Future[_]) => promisedCompletion.complete(Success(())))
    Task.fromFuture(promisedCompletion.future)
  }

  private class ClientChannelImpl(val to: InetSocketAddress)(implicit codec: Codec[M])
      extends Channel[InetSocketAddress, M] {

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
                activation.complete(Success(ctx))
              }

              override def channelInactive(ctx: ChannelHandlerContext): Unit = {
                deactivation.complete(Success(()))
              }
            })
            .addLast(new MessageNotifier(subscribers))

        }
      })

    def initialize: Task[ClientChannelImpl] = toTask(bootstrap.connect(to)).map(_ => this)

    override def sendMessage(message: M): Task[Unit] = {

      val f: Future[Unit] =
        activationF
          .map(ctx => {
            println(
              s"****My Remote Address :${ctx.channel().remoteAddress()}  *******${ctx.channel().id()}*****Client*********My Local Address  ${ctx.channel().localAddress()}"
            )
            ctx.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)))
          })
          .map(_ => ())

      Task.fromFuture(f)
    }

    override def in: Observable[M] = subscribers.messageStream

    override def close(): Task[Unit] = {
      activationF.foreach(ctx => ctx.close())
      Task.fromFuture(deactivationF)
    }

  }

  private class MessageNotifier(val messageSubscribers: Subscribers[M]) extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      println(
        s"******remote*${ctx.channel().remoteAddress()}***local***${ctx.channel().localAddress()}**message tcp channel read *********${codec
          .decode(msg.asInstanceOf[ByteBuf].nioBuffer().asReadOnlyBuffer())}"
      )
      codec.decode(msg.asInstanceOf[ByteBuf].nioBuffer().asReadOnlyBuffer()).map(messageSubscribers.notify)
    }
  }

  private class ServerChannelImpl(val nettyChannel: SocketChannel)(implicit codec: Codec[M])
      extends Channel[InetSocketAddress, M] {

    private val messageSubscribers = new Subscribers[M]

    nettyChannel
      .pipeline()
      .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
      .addLast("frameEncoder", new LengthFieldPrepender(4))
      .addLast(new MessageNotifier(messageSubscribers))
    //def getInetSocketAddress = new InetSocketAddress(nettyChannel.remoteAddress().getAddress, config.remoteHostConfig(nettyChannel.remoteAddress().getAddress))

    override val to: InetSocketAddress = {
      println(s"*My remote  Address: ${nettyChannel.remoteAddress()}  **********${nettyChannel
        .id()}****Server*******My Local Address:  ${nettyChannel.localAddress()}")

      nettyChannel.remoteAddress()
    }

    override def sendMessage(message: M): Task[Unit] = {
      toTask({
        nettyChannel
          .writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)))
      })
    }

    override def in: Observable[M] = messageSubscribers.messageStream

    override def close(): Task[Unit] = {
      toTask(nettyChannel.close())
    }

  }
}

object TCPPeerGroup {
  case class Config(
      bindAddress: InetSocketAddress,
      processAddress: InetSocketAddress,
      remoteHostConfig: Map[InetAddress, Int] = Map.empty[InetAddress, Int]
  )
  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, bindAddress)
    def apply(bindAddress: InetSocketAddress, remoteHostConfig: Map[InetAddress, Int]): Config =
      Config(bindAddress, bindAddress, remoteHostConfig)
  }

}
