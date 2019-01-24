package io.iohk.network.transport.tcp

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import io.iohk.codecs.nio._
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.bytes.ByteArrayEncoder
import io.netty.handler.codec._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

private[network] class NettyTransport(address: InetSocketAddress) {

  private val log = LoggerFactory.getLogger(classOf[NettyTransport])

  private val messageApplications = new ConcurrentHashMap[UUID, MessageApplication[InetSocketAddress]]().asScala

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
          .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4))
          .addLast(new ByteArrayDecoder())
          .addLast(new NettyDecoder())
      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .bind(address)
    .await()

  log.debug(s"Bound to address $address")

  def withMessageApplication[Message](codec: NioCodec[Message], handler: (InetSocketAddress, Message) => Unit): UUID = {
    val uuid = UUID.randomUUID()
    messageApplications.put(uuid, lazyMessageApplication(codec, handler))
    uuid
  }

  def cancelMessageApplication(applicationId: UUID): Boolean =
    messageApplications.remove(applicationId).fold(false)(_ => true)

  def sendMessage(address: InetSocketAddress, message: ByteBuffer): Unit = {
    val activationAdapter = new ChannelInboundHandlerAdapter() {
      override def channelActive(ctx: ChannelHandlerContext): Unit = {
        ctx
          .writeAndFlush(Unpooled.wrappedBuffer(message))
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
      .connect(address)

  }

  def shutdown(): Unit = {
    serverBootstrap.channel().close()
    workerGroup.shutdownGracefully()
  }

  private class NettyDecoder extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val remoteAddress = ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress]

      val b = msg.asInstanceOf[Array[Byte]]

      decodeStream(remoteAddress, java.nio.ByteBuffer.wrap(b), messageApplications.values.toSeq)
        .foreach(_.apply)
    }
  }
}
