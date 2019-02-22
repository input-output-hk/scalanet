package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

import io.iohk.scalanet.messagestream.{MessageStream, MonixMessageStream}
import io.iohk.scalanet.peergroup.PeerGroup.{Lift, TerminalPeerGroup}
import io.netty.bootstrap.Bootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelInitializer}
import monix.eval.Task

import scala.language.higherKinds
import UDPPeerGroup._
import io.iohk.decco.PartialCodec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError

class UDPPeerGroup[F[_]](val config: Config)(implicit liftF: Lift[F])
    extends TerminalPeerGroup[InetSocketAddress, F]() {

  private val workerGroup = new NioEventLoopGroup()

  private val subscribers = new Subscribers[ByteBuffer]()

  private val server = {
   val server =  new Bootstrap()
      .group(workerGroup)
      .channel(classOf[NioDatagramChannel])
      .handler(new ChannelInitializer[NioDatagramChannel]() {
        override def initChannel(ch: NioDatagramChannel): Unit = {
          ch.pipeline.addLast(new ServerInboundHandler)
        }
      })
      .bind(config.bindAddress)
      .syncUninterruptibly()
    println(s"*********Server Binded to the address ${config.bindAddress}")
    server
  }
  private val decoderTable = new DecoderTable()

  override def sendMessage(address: InetSocketAddress, message: ByteBuffer): F[Unit] = {
    liftF(Task(writeUdp(address, message)))
  }

  override def shutdown(): F[Unit] = {
    liftF(Task(server.channel().close().await()))
  }

  override val messageStream: MessageStream[ByteBuffer] =
    new MonixMessageStream(subscribers.monixMessageStream).map(_.duplicate())

  private class ServerInboundHandler extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val b = msg.asInstanceOf[DatagramPacket]
      subscribers.notify(b.content().nioBuffer().asReadOnlyBuffer())
    }
  }

  private def writeUdp(address: InetSocketAddress, data: ByteBuffer): Unit = {
    try{
      val udp = DatagramChannel.open()
      udp.configureBlocking(true)
      println(s"*********Connecting the address ${address} to send message")
      udp.connect(address)
      try {
        udp.write(data)
      } finally {
        udp.close()
      }
    } catch {
      case e:Throwable => e.printStackTrace()
    }
  }

  override val processAddress: InetSocketAddress = config.processAddress

  override def createMessageChannel[MessageType: PartialCodec](): MessageChannel[InetSocketAddress, MessageType, F] =
    new MessageChannel(this, decoderTable)
}

object UDPPeerGroup {

  case class Config(bindAddress: InetSocketAddress, processAddress: InetSocketAddress)

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, bindAddress)
  }

  def create[F[_]](config: Config)(implicit liftF: Lift[F]): Either[InitializationError, UDPPeerGroup[F]] =
    PeerGroup.create(new UDPPeerGroup[F](config), config)

  def createOrThrow[F[_]](config: Config)(implicit liftF: Lift[F]): UDPPeerGroup[F] =
    PeerGroup.createOrThrow(new UDPPeerGroup[F](config), config)

}
