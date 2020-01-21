package io.iohk.scalanet.peergroup

import java.io.IOException
import java.net.{InetSocketAddress, PortUnreachableException}
import java.nio.ByteBuffer
import java.util.concurrent.{ConcurrentHashMap, Semaphore}

import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.{ChannelId, getChannelId, toTask}
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelAlreadyClosedException, ChannelSetupException, MessageMTUException, ServerEvent, TerminalPeerGroup}
import io.iohk.scalanet.peergroup.UDPPeerGroup._
import io.iohk.scalanet.peergroup.UDPPeerGroup.UDPPeerGroupInternals
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicBoolean
import monix.reactive.observables.ConnectableObservable
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal
import scala.concurrent.duration._

/**
  * PeerGroup implementation on top of UDP.
  *
  * @param config bind address etc. See the companion object.
  * @param codec a decco codec for reading writing messages to NIO ByteBuffer.
  * @tparam M the message type.
  */
class UDPPeerGroup[M](val config: Config, cleanupScheduler: Scheduler = Scheduler.singleThread("cleanup-thread"))(
    implicit codec: Codec[M],
    bufferInstantiator: BufferInstantiator[ByteBuffer],
    scheduler: Scheduler
) extends TerminalPeerGroup[InetMultiAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  val serverSubject = ConnectableSubject[ServerEvent[InetMultiAddress, M]]()

  private val workerGroup = new NioEventLoopGroup()

  private[peergroup] val activeChannels = new ConcurrentHashMap[ChannelId, ChannelImpl]()

  // We keep up reference so it will be possible to cancel this infinite task during shutdown
  private val cleanUp = cleanupScheduler.scheduleWithFixedDelay(config.cleanUpInitialDelay, config.cleanUpPeriod) {
    activeChannels.forEach { (key, channel) =>
      if (!channel.isOpen) {
        activeChannels.remove(key)
      }
    }
  }

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
              try {
                val remoteAddress = datagram.sender()
                val localAddress = datagram.recipient()
                val messageE: Either[Codec.Failure, M] = codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())
                log.info(s"Client channel read message with remote $remoteAddress and local $localAddress")

                val channelId = (remoteAddress, localAddress)

                val channel = activeChannels.get(channelId)

                if (channel == null || !channel.isOpen) {
                  // It should never happen as closing client channel closes underlying netty channel, so channelRead should
                  // not execute on this channel
                  throw new IllegalStateException(s"Missing channel instance for channelId $channelId")
                } else {
                  messageE.foreach(message => channel.messageSubject.onNext(message))
                }
              } finally {
                datagram.content().release()
              }
            }

            override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
              val remoteAddress = ctx.channel.remoteAddress().asInstanceOf[InetSocketAddress]
              cause match {
                case _: PortUnreachableException =>
                  // we do not want ugly exception, but we do not close the channel, it is entirely up to user to close not
                  // responding channels
                  log.info("Peer with ip {} not available", remoteAddress)

                case _ =>
                  super.exceptionCaught(ctx, cause)
              }
            }
          })
      }
    })

  private val lock = new Semaphore(1)

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
                val localAddress = processAddress.inetSocketAddress

                val messageE: Either[Codec.Failure, M] = codec.decode(datagram.content().nioBuffer().asReadOnlyBuffer())

                log.info(s"Server read $messageE")
                val serverChannel: NioDatagramChannel = ctx.channel().asInstanceOf[NioDatagramChannel]

                val channelId = getChannelId(remoteAddress, localAddress)

                var newChannel = false

                val channel = activeChannels.compute(
                  channelId,
                  (k, v) => {
                    if (v == null) {
                      // there is no proper channel to push this message, new one needs to be created and user informed
                      newChannel = true
                      new ChannelImpl(
                        serverChannel,
                        localAddress,
                        remoteAddress,
                        ConnectableSubject[M](),
                        UDPPeerGroupInternals.ServerChannel
                      )
                    } else {
                      if (v.isOpen) {
                        // there is proper open channel to push this message on to
                        v
                      } else {
                        // there is proper channel but it is closed by user, new one needs to be created and user informed
                        newChannel = true
                        new ChannelImpl(
                          serverChannel,
                          localAddress,
                          remoteAddress,
                          ConnectableSubject[M](),
                          UDPPeerGroupInternals.ServerChannel
                        )
                      }
                    }
                  }
                )

                if (newChannel) {
                  log.debug(s"Channel with id $channelId NOT found in active channels table. Creating a new one")
                  lock.acquire()
                  serverSubject.onNext(ChannelCreated(channel))
                  lock.release()
                }

                // There is still little possibility for misuse. If user decided to close re-used channel after
                // taking it from map but before server pushes message on to it, `onNext` would be called after `onComplete`
                // which is breach of observer contract.
                // It is worth investigating if it is only theoretical possibility or additional synchronization is needed
                messageE.foreach(m => channel.messageSubject.onNext(m))
              } finally {
                datagram.content().release()
              }
            }
          })
      }
    })

  class ChannelImpl(
      val nettyChannel: NioDatagramChannel,
      localAddress: InetSocketAddress,
      remoteAddress: InetSocketAddress,
      val messageSubject: ConnectableSubject[M],
      channelType: UDPPeerGroupInternals.ChannelType
  ) extends Channel[InetMultiAddress, M] {

    private val open = AtomicBoolean(true)

    log.debug(
      s"Setting up new channel from local address $localAddress " +
        s"to remote address $remoteAddress. Netty channelId is ${nettyChannel.id()}. " +
        s"My channelId is ${getChannelId(remoteAddress, localAddress)}"
    )

    override val to: InetMultiAddress = InetMultiAddress(remoteAddress)

    override def sendMessage(message: M): Task[Unit] = {
      if (!open.get) {

        /**
          *
          * Another design possibility would be to return `Task.now()`, it would be more in spirit of udp i.e
          * sending the message and forgetting about whole world, but on the other hand it could lead to subtle bugs when user
          * of library would like to re-use channels
          *
          */
        Task.raiseError(new ChannelAlreadyClosedException[InetMultiAddress](InetMultiAddress(localAddress), to))
      } else {
        sendMessage(message, localAddress, remoteAddress, nettyChannel)
      }
    }

    override def in: ConnectableObservable[M] = messageSubject

    override def close(): Task[Unit] = {
      for {

        _ <- Task.now(open.flip(false))
        _ <- channelType match {
          case UDPPeerGroupInternals.ServerChannel =>
            // on netty side there is only one channel for accepting incoming connection so if we close it, we will effectively
            // close server
            Task.now(())
          case UDPPeerGroupInternals.ClientChannel =>
            // each client connection creates new channel on netty side
            toTask(nettyChannel.close())
        }
        _ <- Task.eval(messageSubject.onComplete())
      } yield ()
    }

    private def sendMessage(
        message: M,
        sender: InetSocketAddress,
        recipient: InetSocketAddress,
        nettyChannel: NioDatagramChannel
    ): Task[Unit] = {
      val encodedMessage = codec.encode(message)
      toTask(nettyChannel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(encodedMessage), recipient, sender)))
        .onErrorRecoverWith {
          case _: IOException =>
            Task.raiseError(new MessageMTUException[InetMultiAddress](to, encodedMessage.capacity()))
        }
    }

    def isOpen: Boolean = open.get()
  }

  private lazy val serverBind: ChannelFuture = serverBootstrap.bind(config.bindAddress)

  override def initialize(): Task[Unit] =
    toTask(serverBind).map(_ => log.info(s"Server bound to address ${config.bindAddress}")).onErrorRecoverWith {
      case NonFatal(e) => Task.raiseError(InitializationError(e.getMessage, e.getCause))
    }

  override def processAddress: InetMultiAddress = config.processAddress

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = {
    val cf = clientBootstrap.connect(to.inetSocketAddress)
    val ct: Task[NioDatagramChannel] = toTask(cf).map(_ => cf.channel().asInstanceOf[NioDatagramChannel])
    ct.map { nettyChannel =>
        val localAddress = nettyChannel.localAddress()
        log.debug(s"Generated local address for new client is $localAddress")
        val channelId = getChannelId(to.inetSocketAddress, localAddress)

        assert(!activeChannels.containsKey(channelId), s"HOUSTON, WE HAVE A MULTIPLEXING PROBLEM")

        val channel = new ChannelImpl(
          nettyChannel,
          localAddress,
          to.inetSocketAddress,
          ConnectableSubject[M](),
          UDPPeerGroupInternals.ClientChannel
        )
        activeChannels.put(channelId, channel)
        channel
      }
      .onErrorRecoverWith {
        case e: Throwable =>
          Task.raiseError(new ChannelSetupException[InetMultiAddress](to, e))
      }
  }

  override def server(): ConnectableObservable[ServerEvent[InetMultiAddress, M]] = serverSubject

  override def shutdown(): Task[Unit] = {
    for {
      _ <- Task.eval(cleanUp.cancel())
      _ <- Task(serverSubject.onComplete())
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }
}

object UDPPeerGroup {

  val mtu: Int = 16384

  case class Config(
      bindAddress: InetSocketAddress,
      processAddress: InetMultiAddress,
      cleanUpInitialDelay: FiniteDuration = 1 minute,
      cleanUpPeriod: FiniteDuration = 1 minute
  )

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, InetMultiAddress(bindAddress))
  }

  private[scalanet] object UDPPeerGroupInternals {
    sealed abstract class ChannelType
    case object ServerChannel extends ChannelType
    case object ClientChannel extends ChannelType
  }
}
