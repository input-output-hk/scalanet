package io.iohk.scalanet.peergroup

import java.io.IOException
import java.net.{ConnectException, InetAddress, InetSocketAddress}
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore

import io.iohk.decco._
import io.iohk.scalanet.codec.StreamCodec
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelBrokenException, ChannelSetupException, ServerEvent, TerminalPeerGroup}
import io.iohk.scalanet.peergroup.TCPPeerGroup._
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.observables.ConnectableObservable
import org.slf4j.LoggerFactory

import scala.concurrent.Promise
import scala.util.control.NonFatal

/**
  * PeerGroup implementation on top of TCP.
  * FIXME currently this class makes use of netty's LengthFieldPrepender to perform packet reassembly. This means
  * the encoded bytes provided by the callers codec are not identical to the bytes put on the wire (since a
  * length field is prepended to the byte stream). This class therefore cannot be used to talk to general services
  * that are not instances of TCPPeerGroup.
  *
  * @param config bind address etc. See the companion object.
  * @param codec a codec for reading writing messages to NIO ByteBuffer. This must be an instance of {{{StreamCodec}}}
  *              to provide stream delimiting.
  * @tparam M the message type.
  */
class TCPPeerGroup[M](val config: Config)(
    implicit codec: StreamCodec[M],
    bi: BufferInstantiator[ByteBuffer],
    scheduler: Scheduler
) extends TerminalPeerGroup[InetMultiAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)
  private val serverSubject = ConnectableSubject[ServerEvent[InetMultiAddress, M]]()

  private val workerGroup = new NioEventLoopGroup()

  private val lock = new Semaphore(1)

  private val clientBootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
  private val serverBootstrap = new ServerBootstrap()
    .group(workerGroup)
    .channel(classOf[NioServerSocketChannel])
    .childHandler(new ChannelInitializer[SocketChannel]() {
      override def initChannel(ch: SocketChannel): Unit = {
        val newChannel = new ServerChannelImpl[M](ch, codec.cleanSlate, bi)
        //lock.acquire()
        serverSubject.onNext(ChannelCreated(newChannel))
        //lock.release()
        log.debug(s"$processAddress received inbound from ${ch.remoteAddress()}.")
      }
    })
    .option[Integer](ChannelOption.SO_BACKLOG, 128)
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)

  private val serverBind: ChannelFuture = serverBootstrap.bind(config.bindAddress)

  private var alredyInitialized = false

  override def initialize(): Task[Unit] = {
    if(alredyInitialized) {System.out.println("ERROR MULTIPLE TIMES INITIALIZE");throw new Exception("ERROR MULTIPLE TIMES INITIALIZE")}
    alredyInitialized = true
    toTask(serverBind).map(_ => log.info(s"Server bound to address ${config.bindAddress}")).onErrorRecoverWith {
      case NonFatal(e) => Task.raiseError(InitializationError(e.getMessage, e.getCause))
    }
  }

  override def processAddress: InetMultiAddress = config.processAddress

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = {
    new ClientChannelImpl[M](to.inetSocketAddress, clientBootstrap, codec.cleanSlate, bi).initialize
  }

  override def server(): ConnectableObservable[ServerEvent[InetMultiAddress, M]] = serverSubject

  override def shutdown(): Task[Unit] = {
    serverSubject.onComplete()
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }
}

object TCPPeerGroup {
  case class Config(
      bindAddress: InetSocketAddress,
      processAddress: InetMultiAddress,
      remoteHostConfig: Map[InetAddress, Int] = Map.empty[InetAddress, Int]
  )

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, InetMultiAddress(bindAddress))
  }

  private[scalanet] class ServerChannelImpl[M](
      val nettyChannel: SocketChannel,
      codec: StreamCodec[M],
      bi: BufferInstantiator[ByteBuffer]
  )(implicit scheduler: Scheduler)
      extends Channel[InetMultiAddress, M] {

    private val log = LoggerFactory.getLogger(getClass)
    private val messageSubject = ConnectableSubject[M]()

    log.debug(
      s"Creating server channel from ${nettyChannel.localAddress()} to ${nettyChannel.remoteAddress()} with channel id ${nettyChannel.id}"
    )

    nettyChannel
      .pipeline()
      .addLast(new MessageNotifier(messageSubject, codec, bi))

    override val to: InetMultiAddress = InetMultiAddress(nettyChannel.remoteAddress())

    override def sendMessage(message: M): Task[Unit] = {
      toTask(nettyChannel.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)(bi))))
        .onErrorRecoverWith {
          //case cl:java.nio.channels.ClosedChannelException => Task.eval(System.out.println("WRITE IN DISCARTED CHANNEL"))
          case e: IOException =>
            Task.raiseError(new ChannelBrokenException[InetMultiAddress](to, e))
        }
    }

    override def in: ConnectableObservable[M] = messageSubject


    @volatile private var alredyClosed = false
    override def close(): Task[Unit] = {
      if(alredyClosed){System.out.println("CLOSED MULTIPLE TIMES");throw new RuntimeException("CLOSED MULTIPLE TIMES")}
      alredyClosed = true
      messageSubject.onComplete()
      toTask(nettyChannel.close())
    }
  }

  private class ClientChannelImpl[M](
      inetSocketAddress: InetSocketAddress,
      clientBootstrap: Bootstrap,
      codec: StreamCodec[M],
      bi: BufferInstantiator[ByteBuffer]
  )(implicit scheduler: Scheduler)
      extends Channel[InetMultiAddress, M] {

    private val log = LoggerFactory.getLogger(getClass)

    val to: InetMultiAddress = InetMultiAddress(inetSocketAddress)

    private val activation = Promise[ChannelHandlerContext]()
    private val activationF = activation.future
    private val deactivation = Promise[Unit]()
    private val deactivationF = deactivation.future

    private val messageSubject = ConnectableSubject[M]()
    private val bootstrap: Bootstrap = clientBootstrap
      .clone()
      .handler(new ChannelInitializer[SocketChannel]() {
        def initChannel(ch: SocketChannel): Unit = {
          ch.pipeline()
            .addLast(new ChannelInboundHandlerAdapter() {
              override def channelActive(ctx: ChannelHandlerContext): Unit = {
                log.debug(
                  s"Creating client channel from ${ctx.channel().localAddress()} " +
                    s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
                )
                activation.success(ctx)
              }

              override def channelInactive(ctx: ChannelHandlerContext): Unit = {
                deactivation.success(())
              }
            })
            .addLast(new MessageNotifier[M](messageSubject, codec, bi))
        }
      })

    def initialize: Task[ClientChannelImpl[M]] = {
      toTask(bootstrap.connect(inetSocketAddress))
        .onErrorRecoverWith {
          case e: ConnectException =>
            Task.raiseError(new ChannelSetupException[InetMultiAddress](to, e))
        }
        .map(_ => this)
    }

    override def sendMessage(message: M): Task[Unit] = {
      Task
        .fromFuture(activationF)
        .flatMap(ctx => {
          log.debug(
            s"Processing outbound message from local address ${ctx.channel().localAddress()} " +
              s"to remote address ${ctx.channel().remoteAddress()} via channel id ${ctx.channel().id()}"
          )
          toTask(ctx.writeAndFlush(Unpooled.wrappedBuffer(codec.encode(message)(bi))))
        })
        .onErrorRecoverWith {
          case e: IOException =>
            Task.raiseError(new ChannelBrokenException[InetMultiAddress](to, e))
        }
        .map(_ => ())
    }

    override def in: ConnectableObservable[M] = messageSubject

    private var isAlredyClosed = false
    override def close(): Task[Unit] = {
      if(!isAlredyClosed) {
        isAlredyClosed = true
        //messageSubject.onComplete() //TODO
        Task
          .fromFuture(activationF)
          .flatMap(ctx => toTask(ctx.close()))
          .flatMap(_ => Task.fromFuture(deactivationF))
      }else {System.out.println("CLOSE CHANNELL ALREDY CLOSED");Task.eval()}
    }
  }

  private class MessageNotifier[M](
      val messageSubject: ConnectableSubject[M],
      codec: StreamCodec[M],
      bi: BufferInstantiator[ByteBuffer]
  ) extends ChannelInboundHandlerAdapter {

    private val log = LoggerFactory.getLogger(getClass)

    override def channelInactive(channelHandlerContext: ChannelHandlerContext): Unit =
      messageSubject.onComplete()

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val byteBuf = msg.asInstanceOf[ByteBuf]
      try {
        log.debug(
          s"Processing inbound message from remote address ${ctx.channel().remoteAddress()} " +
            s"to local address ${ctx.channel().localAddress()}"
        )
        codec.streamDecode(byteBuf.nioBuffer())(bi).foreach(message => messageSubject.onNext(message))
      } finally {
        byteBuf.release()
      }
    }
  }
}
