package io.iohk.scalanet.external

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.decco.{BufferInstantiator, Codec}
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap

class NettyUDPClient[M](
    initialAddress: InetSocketAddress
)(
    clientCode: M => Unit
)(
    implicit
    codec: Codec[M],
    bufferInstantiator: BufferInstantiator[ByteBuffer]
) {
  private val log = LoggerFactory.getLogger(getClass)
  private val workerGroup = new NioEventLoopGroup()

  private def clientBootstrap =
    new Bootstrap()
      .group(workerGroup)
      .channel(classOf[NioDatagramChannel])
      .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
      .handler(new ChannelInitializer[NioDatagramChannel]() {
        override def initChannel(nettyChannel: NioDatagramChannel): Unit = {
          nettyChannel
            .pipeline()
            .addLast(new ChannelInboundHandlerAdapter() {
              override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
                val datagram = msg.asInstanceOf[DatagramPacket]
                try {
                  val remoteAddress = datagram.sender()
                  val messageE: Either[Codec.Failure, M] =
                    codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())
                  log.info(s"Netty client read $messageE from $remoteAddress")
                  for (m <- messageE) clientCode(m)
                } finally {
                  datagram.content().release()
                }
              }
            })
        }
      })

  private val channels: TrieMap[InetSocketAddress, Channel] = TrieMap[InetSocketAddress, Channel]()

  def sendMessage(to: InetSocketAddress, m: M): Unit = {
    val encodedMessage = codec.encode(m)
    channels.get(to) match {
      case None =>
        log.info(s"Netty client: New channel created for: $to")
        val ch = clientBootstrap
          .connect(to)
          .sync()
          .channel()
        channels += (to -> ch)
        ch.asInstanceOf[NioDatagramChannel]
          .writeAndFlush {
            log.info(s"Netty is seending $encodedMessage to $to")
            new DatagramPacket(Unpooled.wrappedBuffer(encodedMessage), to)
          }
      case Some(ch) =>
        log.info(s"Netty client: Reusing channel for: $to")
        ch.asInstanceOf[NioDatagramChannel]
          .writeAndFlush {
            log.info(s"Netty is seending $encodedMessage to $to")
            new DatagramPacket(Unpooled.wrappedBuffer(encodedMessage), to)
          }
    }
  }

  def shutDown(): Unit = {
    workerGroup.shutdownGracefully()
  }

}
