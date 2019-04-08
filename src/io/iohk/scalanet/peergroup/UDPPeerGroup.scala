package io.iohk.scalanet.peergroup

import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

import io.iohk.decco.auto._
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.netty.bootstrap.Bootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.{DatagramPacket, SocketChannel}
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel._
import monix.eval.Task
import UDPPeerGroup._
import io.iohk.decco.{Codec, PartialCodec, TypeCode}
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.netty.util
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

import scala.concurrent.Promise
import scala.util.Success

class UDPPeerGroup[M](val config: Config)(implicit scheduler: Scheduler, codec: Codec[M]) extends TerminalPeerGroup[InetSocketAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val channelSubscribers = new Subscribers[Channel[InetSocketAddress, M]]

  private val workerGroup = new NioEventLoopGroup()

  private val pduCodec = derivePduCodec

  /**
    * 64 kilobytes is the theoretical maximum size of a complete IP datagram
    * https://stackoverflow.com/questions/9203403/java-datagrampacket-udp-maximum-send-recv-buffer-size
    */
  private val serverBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .handler(new ChannelInitializer[NioDatagramChannel]() {
      override def initChannel(ch: NioDatagramChannel): Unit = {
        val newChannel = new ServerChannelImpl(ch)
        channelSubscribers.notify(newChannel)
      }
    })

  private val serverBind: ChannelFuture = serverBootstrap.bind(config.bindAddress)

  override def initialize(): Task[Unit] =
    toTask(serverBind).map(_ => log.info(s"Server bound to address ${config.bindAddress}"))

  override val processAddress: InetSocketAddress = config.processAddress

  override def client(to: InetSocketAddress): Channel[InetSocketAddress, M] = new ClientChannelImpl(to)

  override def server(): Observable[Channel[InetSocketAddress, M]] = channelSubscribers.messageStream

  override def shutdown(): Task[Unit] =
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()


  private class ClientChannelImpl(val to: InetSocketAddress)(implicit codec: Codec[M]) extends Channel[InetSocketAddress, M] {
    override def sendMessage(message: M): Task[Unit] = {

      val pdu = PDU(processAddress, message)

      Task(writeUdp(to, pduCodec.encode(pdu)))
    }

    override def in: Observable[M] = ???

    override def close(): Task[Unit] = Task.unit
  }

  private class ServerChannelImpl(val nettyChannel: NioDatagramChannel)(implicit codec: Codec[M]) extends Channel[InetSocketAddress, M] {
    private val messageSubscribers = new Subscribers[M]

    nettyChannel.pipeline().addLast(new ServerInboundHandler)
  }

  override def sendMessage[MessageType](address: InetSocketAddress, message: MessageType)(
      implicit codec: Codec[MessageType]
  ): Task[Unit] = {
    val pdu = PDU(processAddress, message)
    val pduCodec = derivePduCodec[MessageType]
    Task(writeUdp(address, pduCodec.encode(pdu)))
  }


  private class ServerInboundHandler extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val b = msg.asInstanceOf[DatagramPacket]
      val remoteAddress: InetSocketAddress = b.sender()
      subscribers.notify((remoteAddress, b.content().nioBuffer().asReadOnlyBuffer()))
    }
  }

  private def writeUdp(address: InetSocketAddress, data: ByteBuffer): Unit = {
    val udp = DatagramChannel.open().setOption[Integer](StandardSocketOptions.SO_SNDBUF, 65536)
    udp.configureBlocking(true)
    udp.connect(address)
    try {
      udp.write(data)
    } finally {
      udp.close()
    }
  }

  override def messageChannel[MessageType](
      implicit codec: Codec[MessageType]
  ): Observable[(InetSocketAddress, MessageType)] = {
    val pduCodec = derivePduCodec[MessageType]
    val messageChannel = new MessageChannel[InetSocketAddress, PDU[MessageType]](this)(pduCodec)
    decoderTable.put(pduCodec.typeCode.id, messageChannel.handleMessage)
    messageChannel.inboundMessages.map {
      case (_, pdu) =>
        (pdu.replyTo, pdu.sdu)
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

  def create(
      config: Config
  )(implicit scheduler: Scheduler): Either[InitializationError, UDPPeerGroup] =
    PeerGroup.create(new UDPPeerGroup(config), config)

  def createOrThrow(config: Config)(implicit scheduler: Scheduler): UDPPeerGroup =
    PeerGroup.createOrThrow(new UDPPeerGroup(config), config)

}
