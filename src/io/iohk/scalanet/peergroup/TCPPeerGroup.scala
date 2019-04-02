package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress

import io.iohk.decco.{Codec, PartialCodec, TypeCode}
import io.iohk.decco.auto._
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.iohk.scalanet.peergroup.TCPPeerGroup.{Config, PDU}
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import io.netty.handler.codec.bytes.ByteArrayEncoder
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

class TCPPeerGroup(val config: Config)(implicit scheduler: Scheduler) extends TerminalPeerGroup[InetSocketAddress]() {
  private val log = LoggerFactory.getLogger(getClass)

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
          .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
          .addLast(nettyDecoder)
      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .bind(config.bindAddress)
    .syncUninterruptibly()

  log.info(s"Server bound to address ${config.bindAddress}")

  override def initialize(): Task[Unit] = Task.unit

  override val processAddress: InetSocketAddress = config.processAddress

  override def sendMessage[MessageType](address: InetSocketAddress, message: MessageType)(
      implicit codec: Codec[MessageType]
  ): Task[Unit] = {

    val pdu = PDU(processAddress, message)
    val pduCodec = derivePduCodec[MessageType]

    val send: Task[Unit] = Task {

      val activationAdapter = new ChannelInboundHandlerAdapter() {
        override def channelActive(ctx: ChannelHandlerContext): Unit = {
          ctx
            .writeAndFlush(Unpooled.wrappedBuffer(pduCodec.encode(pdu)))
            .addListener((_: ChannelFuture) => ctx.channel().close())
        }
      }

      clientBootstrap
        .handler(new ChannelInitializer[SocketChannel]() {
          def initChannel(ch: SocketChannel): Unit = {
            ch.pipeline()
              .addLast("frameEncoder", new LengthFieldPrepender(4))
              .addLast(new ByteArrayEncoder())
              .addLast(activationAdapter)
          }
        })
        .connect(address)
      ()
    }
    send
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

  override def shutdown(): Task[Unit] = {
    Task {
      serverBootstrap.channel().close()
      workerGroup.shutdownGracefully()
      ()
    }
  }

  @Sharable
  private class NettyDecoder extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val remoteAddress = ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress]
      val byteBuffer: ByteBuf = msg.asInstanceOf[ByteBuf]
      subscribers.notify((remoteAddress, byteBuffer.nioBuffer().asReadOnlyBuffer()))
    }
  }

  private def derivePduCodec[MessageType](implicit codec: Codec[MessageType]): Codec[PDU[MessageType]] = {
    implicit val pduTc: TypeCode[PDU[MessageType]] = TypeCode.genTypeCode[PDU, MessageType]
    implicit val mpc: PartialCodec[MessageType] = codec.partialCodec
    Codec[PDU[MessageType]]
  }
}

object TCPPeerGroup {

  case class Config(bindAddress: InetSocketAddress, processAddress: InetSocketAddress)

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, bindAddress)
  }

  case class PDU[T](replyTo: InetSocketAddress, sdu: T)

  def create(
      config: Config
  )(implicit scheduler: Scheduler): Either[InitializationError, TCPPeerGroup] =
    PeerGroup.create(new TCPPeerGroup(config), config)

  def createOrThrow(config: Config)(implicit scheduler: Scheduler): TCPPeerGroup =
    PeerGroup.createOrThrow(new TCPPeerGroup(config), config)
}
