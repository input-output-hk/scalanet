package io.iohk.scalanet.peergroup

import java.io.IOException
import java.net.{InetSocketAddress, PortUnreachableException}
import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.peergroup.Channel.{ChannelEvent, DecodingError, MessageReceived, UnexpectedError}
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.PeerGroup._
import io.iohk.scalanet.peergroup.UDPPeerGroup.UDPPeerGroupInternals.{ChannelType, UdpChannelId}
import io.iohk.scalanet.peergroup.UDPPeerGroup.{UDPPeerGroupInternals, _}
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.util.concurrent.{Future, GenericFutureListener, Promise}
import monix.catnap.ConcurrentQueue
import monix.eval.Task
import monix.execution.{BufferCapacity}
import monix.reactive.Observable
import org.slf4j.LoggerFactory
import scodec.bits.BitVector
import scodec.{Attempt, Codec}

import scala.util.control.NonFatal

/**
  * PeerGroup implementation on top of UDP.
  *
  * @param config bind address etc. See the companion object.
  * @param codec a scodec codec for reading writing messages to NIO ByteBuffer.
  * @tparam M the message type.
  */
class UDPPeerGroup[M](val config: Config)(implicit codec: Codec[M]) extends TerminalPeerGroup[InetMultiAddress, M]() {

  private val log = LoggerFactory.getLogger(getClass)

  val serverQueue = getMessageQueue[ServerEvent[InetMultiAddress, M]]()

  private val workerGroup = new NioEventLoopGroup()

  // all channels in the map are open and active, as upon closing channels are removed from the map
  private[peergroup] val activeChannels = new ConcurrentHashMap[UdpChannelId, ChannelImpl]()

  /**
    * Listener will run when ChannelImpl closed promise will be completed. Channel close promise will run on underlying netty channel
    * scheduler - which means single thread for each channel. This guarantees that after removing channel from the
    * map and calling onComplete, there won't be onNext called in either client or server handler
    *
    */
  private val closeChannelListener = new GenericFutureListener[Future[ChannelImpl]] {
    override def operationComplete(future: Future[ChannelImpl]): Unit = {
      val closedChannel = future.getNow
      removeChannel(closedChannel)
    }
  }

  private def removeChannel(channel: ChannelImpl): Unit = {
    activeChannels.remove(channel.channelId)
    channel.closePromise.removeListener(closeChannelListener)
  }

  private def handleIncomingMessage(channel: ChannelImpl, datagramPacket: DatagramPacket): Unit = {
    codec.decodeValue(BitVector(datagramPacket.content().nioBuffer())) match {
      case Attempt.Successful(msg) =>
        channel.pushEventOnNettyScheduler(MessageReceived(msg))
      case Attempt.Failure(er) =>
        log.debug("Message decoding failed due to {}", er)
        channel.pushEventOnNettyScheduler(DecodingError)
    }
  }

  private def handleError(channelId: UdpChannelId, error: Throwable): Unit = {
    // Inform about error only if channel is available and open
    Option(activeChannels.get(channelId)).foreach { ch =>
      log.debug("Unexpected error {} on channel {}", error: Any, channelId: Any)
      ch.pushEventOnNettyScheduler(UnexpectedError(error))
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
              val remoteAddress = datagram.sender()
              val localAddress = datagram.recipient()
              val udpChannelId = UdpChannelId(ctx.channel().id(), remoteAddress, localAddress)
              try {
                log.info(s"Client channel read message with remote $remoteAddress and local $localAddress")
                Option(activeChannels.get(udpChannelId)).foreach(handleIncomingMessage(_, datagram))
              } catch {
                case NonFatal(e) => handleError(udpChannelId, e)
              } finally {
                datagram.content().release()
              }
            }

            override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
              val channelId = ctx.channel().id()
              val localAddress = ctx.channel().localAddress().asInstanceOf[InetSocketAddress]
              val remoteAddress = ctx.channel.remoteAddress().asInstanceOf[InetSocketAddress]
              val udpChannelId = UdpChannelId(channelId, remoteAddress, localAddress)
              cause match {
                case _: PortUnreachableException =>
                  // we do not want ugly exception, but we do not close the channel, it is entirely up to user to close not
                  // responding channels
                  log.info("Peer with ip {} not available", remoteAddress)

                case _ =>
                  super.exceptionCaught(ctx, cause)
              }
              handleError(udpChannelId, cause)
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
              val localAddress = datagram.recipient()
              log.info(s"Server from $remoteAddress")
              val serverChannel: NioDatagramChannel = ctx.channel().asInstanceOf[NioDatagramChannel]
              val potentialNewChannel = new ChannelImpl(
                serverChannel,
                localAddress,
                remoteAddress,
                getMessageQueue(),
                UDPPeerGroupInternals.ServerChannel
              )
              try {
                Option(activeChannels.putIfAbsent(potentialNewChannel.channelId, potentialNewChannel)) match {
                  case Some(existingChannel) =>
                    handleIncomingMessage(existingChannel, datagram)
                  case None =>
                    log.debug(
                      s"Channel with id ${potentialNewChannel.channelId}. NOT found in active channels table. Creating a new one"
                    )
                    potentialNewChannel.closePromise.addListener(closeChannelListener)
                    serverQueue
                      .offer(ChannelCreated(potentialNewChannel))
                      .runSyncStep(potentialNewChannel.eventLoopAsScheduler)
                    handleIncomingMessage(potentialNewChannel, datagram)
                }
              } catch {
                case NonFatal(e) => handleError(potentialNewChannel.channelId, e)
              } finally {
                datagram.content().release()
              }
            }

            override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
              // We cannot create UdpChannelId as on udp netty server channel there is no remote peer address.
              log.error(s"Unexpected server error ${cause.getMessage}")
            }
          })
      }
    })

  class ChannelImpl(
      val nettyChannel: NioDatagramChannel,
      localAddress: InetSocketAddress,
      remoteAddress: InetSocketAddress,
      val messageQueue: ConcurrentQueue[Task, ChannelEvent[M]],
      channelType: UDPPeerGroupInternals.ChannelType
  ) extends Channel[InetMultiAddress, M] {
    val eventLoopAsScheduler = monix.execution.Scheduler(nettyChannel.eventLoop())

    val closePromise: Promise[ChannelImpl] = nettyChannel.eventLoop().newPromise[ChannelImpl]()

    val channelId = UdpChannelId(nettyChannel.id(), remoteAddress, localAddress)

    def pushEventOnNettyScheduler(ev: ChannelEvent[M]): Unit = {
      println("Push messages on")
      messageQueue.offer(ev).runSyncStep(eventLoopAsScheduler)
    }

    log.debug(
      s"Setting up new channel from local address $localAddress " +
        s"to remote address $remoteAddress. Netty channelId is ${nettyChannel.id()}. " +
        s"My channelId is ${channelId}"
    )

    override val to: InetMultiAddress = InetMultiAddress(remoteAddress)

    override def sendMessage(message: M): Task[Unit] = {
      if (closePromise.isDone) {

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

    //TODO how to finish stream from queue?
    override def in: Observable[ChannelEvent[M]] = {
      Observable.repeatEvalF(messageQueue.poll)
    }

    private def closeNettyChannel(channelType: ChannelType): Task[Unit] = {
      channelType match {
        case UDPPeerGroupInternals.ServerChannel =>
          // on netty side there is only one channel for accepting incoming connection so if we close it, we will effectively
          // close server
          Task.now(())
        case UDPPeerGroupInternals.ClientChannel =>
          // each client connection creates new channel on netty side
          toTask(nettyChannel.close())
      }
    }

    private def closeChannel(): Task[Unit] = {
      for {
        _ <- Task.now(log.debug("Closing channel from {} to {}", localAddress: Any, remoteAddress: Any))
        _ <- closeNettyChannel(channelType)
        _ <- Task.now(log.debug("Channel from {} to {} closed", localAddress: Any, remoteAddress: Any))
      } yield ()
    }

    override def close(): Task[Unit] = {
      if (closePromise.isDone) {
        Task.now(())
      } else {
        closeChannel().doOnFinish(_ => Task(closePromise.trySuccess(this)))
      }
    }

    private def sendMessage(
        message: M,
        sender: InetSocketAddress,
        recipient: InetSocketAddress,
        nettyChannel: NioDatagramChannel
    ): Task[Unit] = {
      log.debug("Sending message {} to peer {}", message, recipient)
      Task.fromTry(codec.encode(message).toTry).flatMap { encodedMessage =>
        val asBuffer = encodedMessage.toByteBuffer
        toTask(nettyChannel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(asBuffer), recipient, sender)))
          .onErrorRecoverWith {
            case _: IOException =>
              Task.raiseError(new MessageMTUException[InetMultiAddress](to, asBuffer.capacity()))
          }
      }
    }
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
        val channel = new ChannelImpl(
          nettyChannel,
          localAddress,
          to.inetSocketAddress,
          getMessageQueue(),
          UDPPeerGroupInternals.ClientChannel
        )
        // By using netty channel id as part of our channel id, we make sure that each client channel is unique
        // therefore there won't be such channels in active channels map already.
        activeChannels.put(channel.channelId, channel)
        channel.closePromise.addListener(closeChannelListener)
        channel
      }
      .onErrorRecoverWith {
        case e: Throwable =>
          log.debug("Udp channel setup failed due to {}", e)
          Task.raiseError(new ChannelSetupException[InetMultiAddress](to, e))
      }
  }

  override def server(): Observable[ServerEvent[InetMultiAddress, M]] =
    Observable.repeatEvalF(serverQueue.poll).cache

  override def shutdown(): Task[Unit] = {
    for {
      _ <- toTask(serverBind.channel().close())
      _ <- toTask(workerGroup.shutdownGracefully())
    } yield ()
  }
}

object UDPPeerGroup {

  def getMessageQueue[M]() = {
    ConcurrentQueue.unsafe[Task, M](BufferCapacity.Unbounded(None), monix.execution.ChannelType.SPMC)
  }

  val mtu: Int = 16384

  case class Config(
      bindAddress: InetSocketAddress,
      processAddress: InetMultiAddress
  )

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, InetMultiAddress(bindAddress))
  }

  private[scalanet] object UDPPeerGroupInternals {
    sealed abstract class ChannelType
    case object ServerChannel extends ChannelType
    case object ClientChannel extends ChannelType

    final case class UdpChannelId(
        nettyChannelId: io.netty.channel.ChannelId,
        remoteAddress: InetSocketAddress,
        localAddress: InetSocketAddress
    )
  }
}
