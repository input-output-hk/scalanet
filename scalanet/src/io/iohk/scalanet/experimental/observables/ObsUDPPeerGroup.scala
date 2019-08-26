package io.iohk.scalanet.experimental.observables

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.scalanet.experimental.createSet
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelSetupException, MessageMTUException}
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable, Observer}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class ObsUDPPeerGroup[M](address: InetSocketAddress)(
    implicit
    codec: Codec[M],
    bufferInstantiator: BufferInstantiator[ByteBuffer],
    scheduler: Scheduler
) extends ObsPeerGroup[InetSocketAddress, M] {

  private val log = LoggerFactory.getLogger(getClass)
  private val workerGroup = new NioEventLoopGroup()

  private val messageHandler =
    createSet[Observer[ObsEnvelope[InetSocketAddress, M]]]

  private val clientBootstrap = new Bootstrap()
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
                val messageE: Either[Codec.Failure, M] = codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())
                println(s"Client read $messageE from $remoteAddress")
                for {
                  message <- messageE
                  o <- messageHandler
                  ch = ctx.channel().asInstanceOf[NioDatagramChannel]
                  env = ObsEnvelope(ObsUDPChannel(ch, ch.remoteAddress()), ch.remoteAddress(), message)
                } o.onNext(env)
              } finally {
                datagram.content().release()
              }
            }
          })
      }
    })

  private val serverBootstrap = new Bootstrap()
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
                val messageE: Either[Codec.Failure, M] = codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())
                log.debug(s"Server read $messageE from $remoteAddress")
                for {
                  message <- messageE
                  o <- messageHandler
                  ch = ctx.channel().asInstanceOf[NioDatagramChannel]
                  env = ObsEnvelope(ObsUDPChannel(ch, ch.remoteAddress()), ch.remoteAddress(), message)
                } o.onNext(env)
              } finally {
                datagram.content().release()
              }
            }
          })
      }
    })

  override def processAddress: InetSocketAddress = address

  private lazy val serverBind: ChannelFuture = serverBootstrap.bind(address)

  override def connect(): Task[Unit] =
    toTask(serverBind)
      .map { _ =>
        log.info(s"Server bound to address $address")
      }
      .onErrorRecoverWith {
        case NonFatal(e) => Task.raiseError(InitializationError(e.getMessage, e.getCause))
      }

  override def client(to: InetSocketAddress): Task[ObsChannel[M]] = {
    val cf = clientBootstrap.connect(to)
    val ct: Task[NioDatagramChannel] = toTask(cf).map(_ => cf.channel().asInstanceOf[NioDatagramChannel])
    ct.map { nettyChannel =>
        log.debug(s"Client generated to talk to $to")
        ObsUDPChannel[M](nettyChannel, to)
      }
      .onErrorRecoverWith {
        case e: Throwable =>
          Task.raiseError(new ChannelSetupException[InetMultiAddress](InetMultiAddress(to), e))
      }
  }

  private val connectionsSubject = ConcurrentSubject[ObsConnection[M]](MulticastStrategy.publish)
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

case class ObsUDPChannel[M](nettyChannel: NioDatagramChannel, remoteAddress: InetSocketAddress)(
    implicit codec: Codec[M],
    bufferInstantiator: BufferInstantiator[ByteBuffer]
) extends ObsChannel[M] {

  private val log = LoggerFactory.getLogger(getClass)

  override def to: InetSocketAddress = remoteAddress

  override def sendMessage(m: M): Task[Unit] = {
    val encodedMessage = codec.encode(m)
    toTask(nettyChannel.writeAndFlush {
      log.debug(s"Sending $encodedMessage to $remoteAddress")
      new DatagramPacket(Unpooled.wrappedBuffer(encodedMessage), remoteAddress)
    }).onErrorRecoverWith {
      case _: IOException =>
        Task.raiseError(new MessageMTUException[InetMultiAddress](InetMultiAddress(to), encodedMessage.capacity()))
    }
  }

  override def close(): Task[Unit] = Task.unit
}
