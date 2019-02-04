package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.CopyOnWriteArraySet

import io.iohk.scalanet.messagestream.{MessageStream, MonixMessageStream}
import io.iohk.scalanet.peergroup.PeerGroup.{Lift, TerminalPeerGroup}
import io.netty.bootstrap.Bootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelInitializer}
import monix.eval.Task
import monix.reactive.observers.Subscriber
import monix.reactive.{Observable, OverflowStrategy}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.higherKinds

class UDPPeerGroup[F[_]](val udpPeerGroupConfig: UDPPeerGroupConfig)(implicit liftF: Lift[F])
    extends TerminalPeerGroup[InetSocketAddress, F]() {

  private val workerGroup = new NioEventLoopGroup()

  private val subscribers = new Subscribers()

  private val server = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .handler(new ChannelInitializer[NioDatagramChannel]() {
      override def initChannel(ch: NioDatagramChannel): Unit = {
        ch.pipeline.addLast(new ServerInboundHandler)
      }
    })
    .bind(udpPeerGroupConfig.address)
    .await()

  override def sendMessage(address: InetSocketAddress, message: ByteBuffer): F[Unit] = {
    liftF(Task(writeUdp(address, message)))
  }

  override def shutdown(): F[Unit] = {
    liftF(Task(server.channel().close().await()))
  }

  override val messageStream: MessageStream[ByteBuffer] = new MonixMessageStream(subscribers.monixMessageStream)

  private class Subscribers {
    val subscriberSet: mutable.Set[Subscriber.Sync[ByteBuffer]] =
      new CopyOnWriteArraySet[Subscriber.Sync[ByteBuffer]]().asScala

    val monixMessageStream: Observable[ByteBuffer] =
      Observable.create(overflowStrategy = OverflowStrategy.Unbounded)((subscriber: Subscriber.Sync[ByteBuffer]) => {

        subscriberSet.add(subscriber)

        () => subscriberSet.remove(subscriber)
      })

    def notify(byteBuffer: ByteBuffer): Unit = subscriberSet.foreach(_.onNext(byteBuffer))
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
}

case class UDPPeerGroupConfig(address: InetSocketAddress)
