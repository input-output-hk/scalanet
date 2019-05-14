package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.iohk.scalanet.peergroup.UDPPeerGroup._
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.util
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.subjects.{PublishSubject, ReplaySubject, Subject}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.util.Success

class UDPPeerGroup[M](val config: Config)(implicit scheduler: Scheduler, codec: Codec[M])
    extends TerminalPeerGroup[InetMultiAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val channelSubject = PublishSubject[Channel[InetMultiAddress, M]]()

  private val workerGroup = new NioEventLoopGroup()

  private val activeChannels = new ConcurrentHashMap[ChannelId, Subject[M, M]]().asScala

  /**
    * 64 kilobytes is the theoretical maximum size of a complete IP datagram
    * https://stackoverflow.com/questions/9203403/java-datagrampacket-udp-maximum-send-recv-buffer-size
    */
  private val bootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .handler(new ChannelInitializer[NioDatagramChannel]() {
      override def initChannel(ch: NioDatagramChannel): Unit = {
        new ChannelImpl(ch, Promise[InetMultiAddress]())
      }
    })

  private val serverBind: ChannelFuture = bootstrap.bind(config.bindAddress)

  override def initialize(): Task[Unit] =
    toTask(serverBind).map(_ => log.info(s"Server bound to address ${config.bindAddress}"))

  override def processAddress: InetMultiAddress = config.processAddress

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = {
    val cf = bootstrap.connect(to.inetSocketAddress)
    val ct: Task[NioDatagramChannel] = toTask(cf).map(_ => cf.channel().asInstanceOf[NioDatagramChannel])
    ct.map(nettyChannel => new ChannelImpl(nettyChannel, Promise().complete(Success(to))))
  }

  override def server(): Observable[Channel[InetMultiAddress, M]] = channelSubject

  override def shutdown(): Task[Unit] = {
    channelSubject.onComplete()
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }

  private class ChannelImpl(
      val nettyChannel: NioDatagramChannel,
      promisedRemoteAddress: Promise[InetMultiAddress]
  )(implicit codec: Codec[M])
      extends ChannelInboundHandlerAdapter
      with Channel[InetMultiAddress, M] {

    nettyChannel.pipeline().addLast(this)

    private val messageSubjectF: Future[Subject[M, M]] = for {
      remoteAddress <- promisedRemoteAddress.future
    } yield {
      log.debug(
        s"New channel created with id ${nettyChannel.id()} from ${nettyChannel.localAddress()} to $remoteAddress"
      )
      activeChannels.getOrElseUpdate(nettyChannel.id, ReplaySubject[M]())
    }

    override def to: InetMultiAddress = Await.result(promisedRemoteAddress.future, Duration.Inf)

    override def sendMessage(message: M): Task[Unit] =
      for {
        remoteAddress <- Task.fromFuture(promisedRemoteAddress.future)
        sendResult <- sendMessage(message, remoteAddress, nettyChannel)
      } yield sendResult

    override def in: Observable[M] = Await.result(messageSubjectF, Duration.Inf)

    override def close(): Task[Unit] = {
      activeChannels.remove(nettyChannel.id)
      Task.unit
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val datagram = msg.asInstanceOf[DatagramPacket]
      val remoteAddress = datagram.sender()
      if (!promisedRemoteAddress.isCompleted) {
        promisedRemoteAddress.complete(Success(new InetMultiAddress(remoteAddress)))
        channelSubject.onNext(this)
      }

      codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer()).map { m =>
        messageSubjectF.foreach { messageSubscribers =>
          log.debug(
            s"Processing inbound message from remote address remote ${ctx.channel().remoteAddress()} " +
              s"to local address ${ctx.channel().localAddress()} via channel id ChannelId ${ctx.channel().id()}."
          )
          messageSubscribers.onNext(m)
        }
      }
    }

    private def sendMessage(message: M, to: InetMultiAddress, nettyChannel: NioDatagramChannel): Task[Unit] = {
      val nettyBuffer = Unpooled.wrappedBuffer(codec.encode(message))
      toTask(nettyChannel.writeAndFlush(new DatagramPacket(nettyBuffer, to.inetSocketAddress, processAddress.inetSocketAddress)))
    }

  }

  private def toTask(f: util.concurrent.Future[_]): Task[Unit] = {
    val promisedCompletion = Promise[Unit]()
    f.addListener((_: util.concurrent.Future[_]) => promisedCompletion.complete(Success(())))
    Task.fromFuture(promisedCompletion.future)
  }
}

object UDPPeerGroup {

  case class Config(bindAddress: InetSocketAddress, processAddress: InetMultiAddress)

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, new InetMultiAddress(bindAddress))
  }
}
