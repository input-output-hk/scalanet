package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.scalanet.messagestream.{MessageStream, MonixMessageStream}
import io.iohk.scalanet.peergroup.PeerGroup.{Lift, TerminalPeerGroup}
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelInitializer, ChannelOption}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import monix.eval.Task
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.observers.Subscriber

import scala.collection.mutable
import scala.language.higherKinds

class TCPPeerGroup[F[_]](tcpPeerGroupConfig: TCPPeerGroupConfig)(implicit liftF: Lift[F]) extends TerminalPeerGroup[InetSocketAddress, F]() {

  // TODO start listening
  println("TCPPeerGroup starting")

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
          .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4))
          .addLast(nettyDecoder)

      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .bind(tcpPeerGroupConfig.address)
    .await()

  //log.debug(s"Bound to address $address")




  override def sendMessage(address: InetSocketAddress, message: ByteBuffer): F[Unit] = {
    val send: Task[Unit] = Task {
      println(s"TCPPeerGroup, send to address $address, message $message")
      // TODO send the message
      ()
    }
    liftF(send)
  }

  override def shutdown(): F[Unit] = {
    liftF(Task {
      println(s"TCPPeerGroup, shutdown")
      // TODO shutdown
      ()
    })
  }




  private class NettyDecoder extends ChannelInboundHandlerAdapter {

    val subscriberSet = mutable.HashSet[Subscriber.Sync[ByteBuffer]]()
    val messageStream: MessageStream[ByteBuffer] = new MonixMessageStream[ByteBuffer](monixMessageStream)

    val monixMessageStream: Observable[ByteBuffer] =
      Observable.create(overflowStrategy = OverflowStrategy.Unbounded)((subscriber:  Subscriber.Sync[ByteBuffer]) => {

        subscriberSet.add(subscriber)

        () => subscriberSet.remove(subscriber)
      })

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val remoteAddress = ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress]
      subscriberSet.foreach(_.onNext(msg.asInstanceOf[ByteBuffer]))
    }
  }

  override val messageStream: MessageStream[ByteBuffer] = nettyDecoder.messageStream
}


case class TCPPeerGroupConfig(address: InetSocketAddress)