package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco.auto._
import io.iohk.decco.{Codec, DecodeFailure, PartialCodec, TypeCode}
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
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.util.Success

class UDPPeerGroup[M](val config: Config)(implicit scheduler: Scheduler, codec: Codec[M])
    extends TerminalPeerGroup[InetSocketAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val channelSubscribers =
    new Subscribers[Channel[InetSocketAddress, M]](s"Channel Subscribers for UDPPeerGroup@'$processAddress'")

  private val workerGroup = new NioEventLoopGroup()

  private val pduCodec = derivePduCodec

  private val activeChannels = new ConcurrentHashMap[ChannelId, Subscribers[M]]().asScala

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
        new ChannelImpl(ch, Promise[InetSocketAddress]())
      }
    })

  private val serverBind: ChannelFuture = bootstrap.bind(config.bindAddress)

  override def initialize(): Task[Unit] =
    toTask(serverBind).map(_ => log.info(s"Server bound to address ${config.bindAddress}"))

  override def processAddress: InetSocketAddress = config.processAddress

  override def client(to: InetSocketAddress): Task[Channel[InetSocketAddress, M]] = {
    val cf = bootstrap.connect(to)
    val ct: Task[NioDatagramChannel] = toTask(cf).map(_ => cf.channel().asInstanceOf[NioDatagramChannel])
    ct.map(nettyChannel => new ChannelImpl(nettyChannel, Promise().complete(Success(to))))
  }

  override def server(): Observable[Channel[InetSocketAddress, M]] = channelSubscribers.messageStream

  override def shutdown(): Task[Unit] =
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()

  private class ChannelImpl(
      val nettyChannel: NioDatagramChannel,
      promisedRemoteAddress: Promise[InetSocketAddress]
  )(implicit codec: Codec[M])
      extends ChannelInboundHandlerAdapter
      with Channel[InetSocketAddress, M] {

    nettyChannel.pipeline().addLast(this)

    private val messageSubscribersF: Future[Subscribers[M]] = for {
      remoteAddress <- promisedRemoteAddress.future
    } yield {
      log.debug(
        s"New channel created with id ${nettyChannel.id()} from ${nettyChannel.localAddress()} to $remoteAddress"
      )
      activeChannels.getOrElseUpdate(nettyChannel.id, new Subscribers[M])
    }

    override def to: InetSocketAddress = Await.result(promisedRemoteAddress.future, Duration.Inf)

    override def sendMessage(message: M): Task[Unit] =
      for {
        remoteAddress <- Task.fromFuture(promisedRemoteAddress.future)
        sendResult <- sendMessage(message, remoteAddress, nettyChannel)
      } yield sendResult

    override def in: Observable[M] = Await.result(messageSubscribersF, Duration.Inf).messageStream

    override def close(): Task[Unit] = {
      activeChannels.remove(nettyChannel.id)
      Task.unit
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val datagram = msg.asInstanceOf[DatagramPacket]
      val remoteAddress = datagram.sender()
      if (!promisedRemoteAddress.isCompleted) {
        promisedRemoteAddress.complete(Success(remoteAddress))
        channelSubscribers.notify(this)
      }

      val pdu: Either[DecodeFailure, PDU[M]] = pduCodec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())
      pdu match {
        case Right(PDU(_, sdu)) =>
          messageSubscribersF.foreach(messageSubscribers => messageSubscribers.notify(sdu))
        case Left(failure) =>
          log.info(s"Encountered decode failure $failure in inbound message to UDPPeerGroup@'$processAddress'")
      }
    }

    private def sendMessage(message: M, to: InetSocketAddress, nettyChannel: NioDatagramChannel): Task[Unit] = {
      val pdu = PDU(processAddress, message)
      val nettyBuffer = Unpooled.wrappedBuffer(pduCodec.encode(pdu))
      toTask(nettyChannel.writeAndFlush(new DatagramPacket(nettyBuffer, to, processAddress)))
    }
  }

  private def derivePduCodec[MessageType](implicit codec: Codec[MessageType]): Codec[PDU[MessageType]] = {
    implicit val pduTc: TypeCode[PDU[MessageType]] = TypeCode.genTypeCode[PDU, MessageType]
    implicit val mpc: PartialCodec[MessageType] = codec.partialCodec
    Codec[PDU[MessageType]]
  }

  private def toTask(f: util.concurrent.Future[_]): Task[Unit] = {
    val promisedCompletion = Promise[Unit]()
    f.addListener((_: util.concurrent.Future[_]) => promisedCompletion.complete(Success(())))
    Task.fromFuture(promisedCompletion.future)
  }
}

object UDPPeerGroup {

  case class Config(bindAddress: InetSocketAddress, processAddress: InetSocketAddress)

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, bindAddress)
  }
  case class PDU[T](replyTo: InetSocketAddress, sdu: T)
}
