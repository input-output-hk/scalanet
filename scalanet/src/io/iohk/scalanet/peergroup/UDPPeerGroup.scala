package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.{getChannelId, toTask}
import io.iohk.scalanet.peergroup.UDPPeerGroup._
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel
import monix.eval.Task
import monix.reactive.Observable
import monix.reactive.subjects.{PublishSubject, ReplaySubject, Subject}
import org.slf4j.LoggerFactory
import InetPeerGroupUtils.ChannelId
import scala.collection.JavaConverters._

/**
  * PeerGroup implementation on top of UDP.
  *
  * @param config bind address etc. See the companion object.
  * @param codec a decco codec for reading writing messages to NIO ByteBuffer.
  * @tparam M the message type.
  */
class UDPPeerGroup[M](val config: Config)(implicit codec: Codec[M], bufferInstantiator: BufferInstantiator[ByteBuffer])
    extends TerminalPeerGroup[InetMultiAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val channelSubject = PublishSubject[Channel[InetMultiAddress, M]]()

  private val workerGroup = new NioEventLoopGroup()

  private val activeChannels = new ConcurrentHashMap[ChannelId, ChannelImpl]().asScala

  /**
    * 64 kilobytes is the theoretical maximum size of a complete IP datagram
    * https://stackoverflow.com/questions/9203403/java-datagrampacket-udp-maximum-send-recv-buffer-size
    */
  private val clientBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .handler(new ChannelInitializer[NioDatagramChannel]() {
      override def initChannel(nettyChannel: NioDatagramChannel): Unit = {
        nettyChannel
          .pipeline()
          .addLast(new channel.ChannelInboundHandlerAdapter() {
            override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
              val datagram = msg.asInstanceOf[DatagramPacket]
              val remoteAddress = datagram.sender()
              val localAddress = datagram.recipient()
              val messageE: Either[Codec.Failure, M] = codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())
              log.debug(s"Client channel read message $messageE with remote $remoteAddress and local $localAddress")

              val channelId = getChannelId(remoteAddress, localAddress)

              if (!activeChannels.contains(channelId)) {
                throw new IllegalStateException(s"Missing channel instance for channelId $channelId")
              }

              val channel = activeChannels(channelId)
              messageE.foreach(message => channel.messageSubject.onNext(message))
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
              val remoteAddress = datagram.sender()
              val localAddress = processAddress.inetSocketAddress //datagram.recipient()

              val messageE: Either[Codec.Failure, M] = codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())

              log.debug(s"Server read $messageE")
              val nettyChannel: NioDatagramChannel = ctx.channel().asInstanceOf[NioDatagramChannel]
              val channelId = getChannelId(remoteAddress, localAddress)

              if (activeChannels.contains(channelId)) {
                log.debug(s"Channel with id $channelId found in active channels table.")
                val channel = activeChannels(channelId)
                messageE.foreach(message => channel.messageSubject.onNext(message))
              } else {
                val channel = new ChannelImpl(nettyChannel, localAddress, remoteAddress, ReplaySubject[M]())
                log.debug(s"Channel with id $channelId NOT found in active channels table. Creating a new one")
                activeChannels.put(channelId, channel)
                channelSubject.onNext(channel)
                messageE.foreach(message => channel.messageSubject.onNext(message))
              }
            }
          })
      }
    })

  class ChannelImpl(
      val nettyChannel: NioDatagramChannel,
      localAddress: InetSocketAddress,
      remoteAddress: InetSocketAddress,
      val messageSubject: Subject[M, M]
  ) extends Channel[InetMultiAddress, M] {

    log.debug(
      s"Setting up new channel from local address $localAddress " +
        s"to remote address $remoteAddress. Netty channelId is ${nettyChannel.id()}. " +
        s"My channelId is ${getChannelId(remoteAddress, localAddress)}"
    )

    override val to: InetMultiAddress = InetMultiAddress(remoteAddress)

    override def sendMessage(message: M): Task[Unit] = sendMessage(message, localAddress, remoteAddress, nettyChannel)

    override def in: Observable[M] = messageSubject

    override def close(): Task[Unit] = {
      messageSubject.onComplete()
      Task.unit
    }

    private def sendMessage(
        message: M,
        sender: InetSocketAddress,
        recipient: InetSocketAddress,
        nettyChannel: NioDatagramChannel
    ): Task[Unit] = {
      val nettyBuffer = Unpooled.wrappedBuffer(codec.encode(message))
      toTask(nettyChannel.writeAndFlush(new DatagramPacket(nettyBuffer, recipient, sender)))
    }
  }

  private val serverBind: ChannelFuture = serverBootstrap.bind(config.bindAddress)

  override def initialize(): Task[Unit] =
    toTask(serverBind).map(_ => log.info(s"Server bound to address ${config.bindAddress}"))

  override def processAddress: InetMultiAddress = config.processAddress

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = {
    val cf = clientBootstrap.connect(to.inetSocketAddress)
    val ct: Task[NioDatagramChannel] = toTask(cf).map(_ => cf.channel().asInstanceOf[NioDatagramChannel])
    ct.map { nettyChannel =>
      val localAddress = nettyChannel.localAddress()
      log.debug(s"Generated local address for new client is $localAddress")
      val channelId = getChannelId(to.inetSocketAddress, localAddress)

      assert(!activeChannels.contains(channelId), s"HOUSTON, WE HAVE A MULTIPLEXING PROBLEM")

      val channel = new ChannelImpl(nettyChannel, localAddress, to.inetSocketAddress, ReplaySubject[M]())
      activeChannels.put(channelId, channel)
      channel
    }
  }

  override def server(): Observable[Channel[InetMultiAddress, M]] = channelSubject

  override def shutdown(): Task[Unit] = {
    channelSubject.onComplete()
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }
}

object UDPPeerGroup {

  case class Config(bindAddress: InetSocketAddress, processAddress: InetMultiAddress)

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, InetMultiAddress(bindAddress))
  }
}
