package io.iohk.scalanet.peergroup

import java.net.{InetAddress, InetSocketAddress}
import java.util.UUID
import java.util.UUID.randomUUID

import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.iohk.scalanet.peergroup.TCPPeerGroup.{Config, TcpAddress}
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import io.netty.handler.codec.bytes.ByteArrayEncoder
import monix.eval.Task
import monix.execution.Scheduler

class TCPPeerGroup(val config: Config)(implicit scheduler: Scheduler) extends TerminalPeerGroup[InetAddress]() {

  private val nettyDecoder = new NettyDecoder()
  private val workerGroup = new NioEventLoopGroup()

  private val clientBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

  private val serverBootstrap = new ServerBootstrap()
    .group(workerGroup)
    .channel(classOf[NioServerSocketChannel])
    .childHandler(new ChannelInitializer[SocketChannel]() {
      override def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline()
          .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
          .addLast(nettyDecoder)
      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .bind(config.bindAddress)
    .syncUninterruptibly()

  override val processAddress: InetAddress = config.processAddress

  override def sendMessage[MessageType](address: InetAddress, message: MessageType)(
      implicit codec: Codec[MessageType]
  ): Task[Unit] = {

    val tcpAddress = getTcpAddress(address)

    val send: Task[Unit] = Task {

      val activationAdapter = new ChannelInboundHandlerAdapter() {
        override def channelActive(ctx: ChannelHandlerContext): Unit = {
          ctx
            .writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)))
            .addListener((_: ChannelFuture) => ctx.channel().close())
        }
      }

      clientBootstrap
        .handler(new ChannelInitializer[SocketChannel]() {
          def initChannel(ch: SocketChannel): Unit = {
            ch.pipeline()
              .addLast("frameEncoder", new LengthFieldPrepender(4))
              .addLast(new ByteArrayEncoder())
              .addLast(activationAdapter)
          }
        })
        .connect(tcpAddress)
      ()
    }
    send
  }

  override def shutdown(): Task[Unit] = {
    Task {
      serverBootstrap.channel().close()
      workerGroup.shutdownGracefully()
      ()
    }
  }

  override def initialize(): Task[Unit] = Task.unit

  @Sharable
  private class NettyDecoder extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val remoteAddress = ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress].getAddress
      val byteBuffer: ByteBuf = msg.asInstanceOf[ByteBuf]
      subscribers.notify((remoteAddress, byteBuffer.nioBuffer().asReadOnlyBuffer()))
    }
  }

  private def getTcpAddress(address: InetAddress): InetSocketAddress = ???
}

object TCPPeerGroup {

  case class Config(bindAddress: InetSocketAddress, processAddress: InetAddress)

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, bindAddress.getAddress)
  }

  def create(
      config: Config
  )(implicit scheduler: Scheduler): Either[InitializationError, TCPPeerGroup] =
    PeerGroup.create(new TCPPeerGroup(config), config)

  def createOrThrow(config: Config)(implicit scheduler: Scheduler): TCPPeerGroup =
    PeerGroup.createOrThrow(new TCPPeerGroup(config), config)

}
