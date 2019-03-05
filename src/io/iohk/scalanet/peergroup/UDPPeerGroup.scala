package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.netty.bootstrap.Bootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelInitializer}
import monix.eval.Task

import UDPPeerGroup._
import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import monix.execution.Scheduler
import org.slf4j.LoggerFactory

class UDPPeerGroup(val config: Config)(implicit scheduler: Scheduler) extends TerminalPeerGroup[InetSocketAddress]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val workerGroup = new NioEventLoopGroup()

  private val subscribers = new Subscribers[ByteBuffer]()

  private val server = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .handler(new ChannelInitializer[NioDatagramChannel]() {
      override def initChannel(ch: NioDatagramChannel): Unit = {
        ch.pipeline.addLast(new ServerInboundHandler)
      }
    })
    .bind(config.bindAddress)
    .syncUninterruptibly()
  log.info(s"Server bound to address ${config.bindAddress}")

  override def sendMessage[T](address: InetSocketAddress, message: T)(implicit codec: Codec[T]): Task[Unit] = {
    Task(writeUdp(address, codec.encode(message)))
  }

  override def shutdown(): Task[Unit] = {
    Task(server.channel().close().await())
  }

  subscribers.messageStream.foreach { byteBuffer =>
    Codec.decodeFrame(decoderTable.entries, 0, byteBuffer)
  }

  private class ServerInboundHandler extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val b = msg.asInstanceOf[DatagramPacket]
      subscribers.notify(b.content().nioBuffer().asReadOnlyBuffer())
    }
  }

  private def writeUdp(address: InetSocketAddress, data: ByteBuffer): Unit = {
    val udp = DatagramChannel.open()
    udp.configureBlocking(true)
    udp.connect(address)
    try {
      udp.write(data)
    } finally {
      udp.close()
    }
  }

  override val processAddress: InetSocketAddress = config.processAddress

  override def initialize(): Task[Unit] = Task.unit
}

object UDPPeerGroup {

  case class Config(bindAddress: InetSocketAddress, processAddress: InetSocketAddress)

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, bindAddress)
  }

  def create(
      config: Config
  )(implicit scheduler: Scheduler): Either[InitializationError, UDPPeerGroup] =
    PeerGroup.create(new UDPPeerGroup(config), config)

  def createOrThrow(config: Config)(implicit scheduler: Scheduler): UDPPeerGroup =
    PeerGroup.createOrThrow(new UDPPeerGroup(config), config)

}
