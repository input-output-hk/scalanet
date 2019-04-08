package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress

import io.iohk.decco.{Codec, DecodeFailure, PartialCodec, TypeCode}
import io.iohk.decco.auto._
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.iohk.scalanet.peergroup.TCPPeerGroup.{Config, PDU}
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import io.netty.handler.codec.bytes.ByteArrayEncoder
import io.netty.util
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, Promise}
import scala.util.Success

class TCPPeerGroup[M](val config: Config)(implicit scheduler: Scheduler, codec: Codec[M])
    extends TerminalPeerGroup[InetSocketAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val channelSubscribers = new Subscribers[Channel[InetSocketAddress, M]]

  private val pduCodec = derivePduCodec

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
        val newChannel = new ServerChannelImpl(ch)
        channelSubscribers.notify(newChannel)
      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

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

  private def toTask(f: util.concurrent.Future[_]): Task[Unit] = {
    val promisedCompletion = Promise[Unit]()
    f.addListener((_: util.concurrent.Future[_]) => promisedCompletion.complete(Success(())))
    Task.fromFuture(promisedCompletion.future)
  }

  private def derivePduCodec(implicit codec: Codec[M]): Codec[PDU[M]] = {
    implicit val pduTc: TypeCode[PDU[M]] = TypeCode.genTypeCode[PDU, M]
    implicit val mpc: PartialCodec[M] = codec.partialCodec
    Codec[PDU[M]]
  }

  private class ClientChannelImpl(val to: InetSocketAddress)(implicit codec: Codec[M])
      extends Channel[InetSocketAddress, M] {

    private val activation = Promise[ChannelHandlerContext]()
    private val activationF = activation.future
    private val deactivation = Promise[Unit]()
    private val deactivationF = deactivation.future

    clientBootstrap
      .handler(new ChannelInitializer[SocketChannel]() {
        def initChannel(ch: SocketChannel): Unit = {
          ch.pipeline()
            .addLast("frameEncoder", new LengthFieldPrepender(4))
            .addLast(new ByteArrayEncoder())
            .addLast(new ChannelInboundHandlerAdapter() {
              override def channelActive(ctx: ChannelHandlerContext): Unit = {
                activation.complete(Success(ctx))
              }

              override def channelInactive(ctx: ChannelHandlerContext): Unit = {
                deactivation.complete(Success(()))
              }
            })
        }
      })
      .connect(to)

    override def sendMessage(message: M): Task[Unit] = {

      val pdu = PDU(processAddress, message)

      val f: Future[Unit] =
        activationF.map(ctx => ctx.writeAndFlush(Unpooled.wrappedBuffer(pduCodec.encode(pdu)))).map(_ => ())

      Task.fromFuture(f)
    }

    override def in: Observable[M] = ???

    override def close(): Task[Unit] = {
      activationF.foreach(ctx => ctx.close())
      Task.fromFuture(deactivationF)
    }
  }

  private class MessageNotifier(val messageSubscribers: Subscribers[M]) extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val pdu: Either[DecodeFailure, PDU[M]] = pduCodec.decode(msg.asInstanceOf[ByteBuf].nioBuffer().asReadOnlyBuffer())
      pdu match {
        case Right(PDU(_, sdu)) =>
          messageSubscribers.notify(sdu)
        case _ =>
          ???
      }
    }
  }

  private class ServerChannelImpl(val nettyChannel: SocketChannel)(implicit codec: Codec[M])
      extends Channel[InetSocketAddress, M] {

    private val messageSubscribers = new Subscribers[M]

    nettyChannel
      .pipeline()
      .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
      .addLast(new MessageNotifier(messageSubscribers))

    override val to: InetSocketAddress = nettyChannel.remoteAddress()

    override def sendMessage(message: M): Task[Unit] = {
      val pdu = PDU(processAddress, message)
      toTask(nettyChannel
        .writeAndFlush(Unpooled.wrappedBuffer(pduCodec.encode(pdu))))
    }

    override def in: Observable[M] = messageSubscribers.messageStream

    override def close(): Task[Unit] = {
      toTask(nettyChannel.close())
    }
  }
}

object TCPPeerGroup {

  case class Config(bindAddress: InetSocketAddress, processAddress: InetSocketAddress)

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, bindAddress)
  }

  case class PDU[T](replyTo: InetSocketAddress, sdu: T)
}
