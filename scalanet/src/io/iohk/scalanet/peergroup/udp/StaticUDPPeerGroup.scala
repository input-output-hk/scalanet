package io.iohk.scalanet.peergroup.udp

import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.Resource
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import io.iohk.scalanet.peergroup.{Channel, Release, InetMultiAddress, CloseableQueue}
import io.iohk.scalanet.peergroup.Channel.{ChannelEvent, MessageReceived, DecodingError, UnexpectedError}
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.{
  ServerEvent,
  TerminalPeerGroup,
  MessageMTUException,
  ChannelAlreadyClosedException
}
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.{
  RecvByteBufAllocator,
  ChannelOption,
  ChannelInitializer,
  ChannelHandlerContext,
  ChannelInboundHandlerAdapter
}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import java.io.IOException
import java.net.{InetSocketAddress, PortUnreachableException}
import monix.eval.Task
import monix.execution.{Scheduler, ChannelType}
import scala.util.control.NonFatal
import scodec.{Codec, Attempt}
import scodec.bits.BitVector

/**
  * PeerGroup implementation on top of UDP that uses the same local port
  * when creating channels to remote addresses as the one it listens on
  * for incoming messages.
  *
  * This makes it compatible with protocols that update the peer's port
  * to the last one it sent a message from.
  *
  * It also means that incoming messages cannot be tied to a specific channel,
  * so if multiple channels are open to the same remote address,
  * they will all see the same messages. The incoming responses will also
  * cause a server channel to be opened, where response type messages have
  * to be discarded, and the server channel can be discarded if there's no
  * request type message for a long time.
  *
  * @tparam M the message type.
  */
class StaticUDPPeerGroup[M] private (
    config: StaticUDPPeerGroup.Config,
    workerGroup: NioEventLoopGroup,
    isShutdownRef: Ref[Task, Boolean],
    serverQueue: CloseableQueue[ServerEvent[InetMultiAddress, M]],
    serverChannelSemaphore: Semaphore[Task],
    serverChannelsRef: Ref[Task, Map[InetSocketAddress, StaticUDPPeerGroup.ChannelAlloc[M]]],
    clientChannelsRef: Ref[Task, Map[InetSocketAddress, Set[StaticUDPPeerGroup.ChannelAlloc[M]]]]
)(implicit scheduler: Scheduler, codec: Codec[M])
    extends TerminalPeerGroup[InetMultiAddress, M]
    with StrictLogging {

  import StaticUDPPeerGroup.{ChannelImpl, ChannelAlloc}

  override protected val s = scheduler

  override val processAddress = config.processAddress

  private val localAddress = config.bindAddress

  override def nextServerEvent() =
    serverQueue.next()

  def channelCount: Task[Int] =
    for {
      serverChannels <- serverChannelsRef.get
      clientChannels <- clientChannelsRef.get
    } yield serverChannels.size + clientChannels.values.map(_.size).sum

  private val raiseIfShutdown =
    isShutdownRef.get
      .ifM(Task.raiseError(new IllegalStateException("The peer group has already been shut down.")), Task.unit)

  /** Create a new channel from the local server port to the remote address. */
  override def client(to: InetMultiAddress): Resource[Task, Channel[InetMultiAddress, M]] = {
    for {
      _ <- Resource.liftF(raiseIfShutdown)
      remoteAddress = to.inetSocketAddress
      channel <- Resource {
        ChannelImpl[M](
          nettyChannel = serverBinding.channel,
          localAddress = localAddress,
          remoteAddress = remoteAddress,
          role = ChannelImpl.Client,
          capacity = config.channelCapacity
        ).allocated.flatMap {
          case (channel, release) =>
            // Register the channel as belonging to the remote address so that
            // we can replicate incoming messages to it later.
            val add = for {
              _ <- addClientChannel(channel -> release)
              _ <- Task(logger.debug(s"Added UDP client channel from $localAddress to $remoteAddress"))
            } yield ()

            val remove = for {
              _ <- removeClientChannel(channel -> release)
              _ <- release
              _ <- Task(logger.debug(s"Removed UDP client channel from $localAddress to $remoteAddress"))
            } yield ()

            add.as(channel -> remove)
        }
      }
    } yield channel
  }

  private def addClientChannel(channel: ChannelAlloc[M]) =
    clientChannelsRef.update { clientChannels =>
      val remoteAddress = channel._1.to.inetSocketAddress
      val current = clientChannels.getOrElse(remoteAddress, Set.empty)
      clientChannels.updated(remoteAddress, current + channel)
    }

  private def removeClientChannel(channel: ChannelAlloc[M]) =
    clientChannelsRef.update { clientChannels =>
      val remoteAddress = channel._1.to.inetSocketAddress
      val current = clientChannels.getOrElse(remoteAddress, Set.empty)
      val removed = current - channel
      if (removed.isEmpty) clientChannels - remoteAddress else clientChannels.updated(remoteAddress, removed)
    }

  private def getOrCreateServerChannel(remoteAddress: InetSocketAddress): Task[ChannelImpl[M]] = {
    serverChannelsRef.get.map(_.get(remoteAddress)).flatMap {
      case Some((channel, _)) =>
        Task.pure(channel)

      case None =>
        // Use a semaphore to make sure we only create one channel.
        // This way we can handle incoming messages asynchronously.
        serverChannelSemaphore.withPermit {
          serverChannelsRef.get.map(_.get(remoteAddress)).flatMap {
            case Some((channel, _)) =>
              Task.pure(channel)

            case None =>
              ChannelImpl[M](
                nettyChannel = serverBinding.channel,
                localAddress = config.bindAddress,
                remoteAddress = remoteAddress,
                role = ChannelImpl.Server,
                capacity = config.channelCapacity
              ).allocated.flatMap {
                case (channel, release) =>
                  val remove = for {
                    _ <- serverChannelsRef.update(_ - remoteAddress)
                    _ <- release
                    _ <- Task(logger.debug(s"Removed UDP server channel from $remoteAddress to $localAddress"))
                  } yield ()

                  val add = for {
                    _ <- serverChannelsRef.update(_.updated(remoteAddress, channel -> release))
                    _ <- serverQueue.offer(ChannelCreated(channel, remove))
                    _ <- Task(logger.debug(s"Added UDP server channel from $remoteAddress to $localAddress"))
                  } yield channel

                  add.as(channel)
              }
          }
        }
    }
  }

  private def getClientChannels(remoteAddress: InetSocketAddress): Task[Iterable[ChannelImpl[M]]] =
    clientChannelsRef.get.map {
      _.getOrElse(remoteAddress, Set.empty).toIterable.map(_._1)
    }

  private def getChannels(remoteAddress: InetSocketAddress): Task[Iterable[ChannelImpl[M]]] =
    isShutdownRef.get.ifM(
      Task.pure(Iterable.empty),
      for {
        serverChannel <- getOrCreateServerChannel(remoteAddress)
        clientChannels <- getClientChannels(remoteAddress)
        channels = Iterable(serverChannel) ++ clientChannels
      } yield channels
    )

  private def replicateToChannels(remoteAddress: InetSocketAddress)(
      f: ChannelImpl[M] => Task[Unit]
  ): Task[Unit] =
    for {
      channels <- getChannels(remoteAddress)
      _ <- Task.parTraverseUnordered(channels)(f).executeOn(scheduler)
    } yield ()

  /** Replicate the incoming message to the server channel and all client channels connected to the remote address. */
  private def handleMessage(
      remoteAddress: InetSocketAddress,
      maybeMessage: Attempt[M]
  ): Unit =
    executeAsync {
      replicateToChannels(remoteAddress)(_.handleMessage(maybeMessage))
    }

  private def handleError(remoteAddress: InetSocketAddress, error: Throwable): Unit =
    executeAsync {
      replicateToChannels(remoteAddress)(_.handleError(error))
    }

  // Execute the task asynchronously. Has to be thread safe.
  private def executeAsync(task: Task[Unit]): Unit = {
    task.runAsyncAndForget
  }

  private def tryDecodeDatagram(datagram: DatagramPacket): Attempt[M] =
    codec.decodeValue(BitVector(datagram.content.nioBuffer)) match {
      case failure @ Attempt.Failure(err) =>
        logger.debug(s"Message decoding failed due to ${err}", err)
        failure
      case success =>
        success
    }

  private lazy val serverBinding =
    new Bootstrap()
      .group(workerGroup)
      .channel(classOf[NioDatagramChannel])
      .option[RecvByteBufAllocator](
        ChannelOption.RCVBUF_ALLOCATOR,
        new io.netty.channel.DefaultMaxBytesRecvByteBufAllocator()
      )
      .handler(new ChannelInitializer[NioDatagramChannel]() {
        override def initChannel(nettyChannel: NioDatagramChannel): Unit = {
          nettyChannel
            .pipeline()
            .addLast(new ChannelInboundHandlerAdapter() {
              override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
                val datagram = msg.asInstanceOf[DatagramPacket]
                val remoteAddress = datagram.sender
                try {
                  logger.debug(s"Server channel at $localAddress read message from $remoteAddress")
                  handleMessage(remoteAddress, tryDecodeDatagram(datagram))
                } catch {
                  case NonFatal(ex) =>
                    handleError(remoteAddress, ex)
                } finally {
                  datagram.content().release()
                  ()
                }
              }

              override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
                val channelId = ctx.channel().id()
                val remoteAddress = ctx.channel.remoteAddress().asInstanceOf[InetSocketAddress]
                cause match {
                  case _: PortUnreachableException =>
                    // We do not want ugly exception, but we do not close the channel,
                    // it is entirely up to user to close not responding channels.
                    logger.info(s"Peer with ip $remoteAddress not available")
                  case _ =>
                    super.exceptionCaught(ctx, cause)
                }
                cause match {
                  case NonFatal(ex) =>
                    handleError(remoteAddress, ex)
                }
              }
            })

          ()
        }
      })
      .bind(localAddress)

  // Wait until the server is bound.
  private def initialize(): Task[Unit] =
    for {
      _ <- raiseIfShutdown
      _ <- toTask(serverBinding).onErrorRecoverWith {
        case NonFatal(ex) =>
          Task.raiseError(InitializationError(ex.getMessage, ex.getCause))
      }
      _ <- Task(logger.info(s"Server bound to address ${config.bindAddress}"))
    } yield ()

  private def shutdown(): Task[Unit] = {
    for {
      _ <- Task(logger.info(s"Shutting down UDP peer group for peer ${config.processAddress}"))
      // Mark the group as shutting down to stop accepting incoming connections.
      _ <- isShutdownRef.set(true)
      _ <- serverQueue.close(discard = true)
      // Release client channels.
      _ <- clientChannelsRef.get.map(_.values.flatten.toList.map(_._2.attempt).sequence)
      // Release server channels.
      _ <- serverChannelsRef.get.map(_.values.toList.map(_._2.attempt).sequence)
      // Stop the in and outgoing traffic.
      _ <- toTask(serverBinding.channel.close())
    } yield ()
  }

}

object StaticUDPPeerGroup extends StrictLogging {
  case class Config(
      bindAddress: InetSocketAddress,
      processAddress: InetMultiAddress,
      channelCapacity: Int
  )
  object Config {
    def apply(bindAddress: InetSocketAddress, channelCapacity: Int = 0): Config =
      Config(bindAddress, InetMultiAddress(bindAddress), channelCapacity)
  }

  private type ChannelAlloc[M] = (ChannelImpl[M], Release)

  def apply[M: Codec](config: Config)(implicit scheduler: Scheduler): Resource[Task, StaticUDPPeerGroup[M]] =
    makeEventLoop.flatMap { workerGroup =>
      Resource.make {
        for {
          isShutdownRef <- Ref[Task].of(false)
          serverQueue <- CloseableQueue.unbounded[ServerEvent[InetMultiAddress, M]](ChannelType.SPMC)
          serverChannelSemaphore <- Semaphore[Task](1)
          serverChannelsRef <- Ref[Task].of(Map.empty[InetSocketAddress, ChannelAlloc[M]])
          clientChannelsRef <- Ref[Task].of(Map.empty[InetSocketAddress, Set[ChannelAlloc[M]]])
          peerGroup = new StaticUDPPeerGroup[M](
            config,
            workerGroup,
            isShutdownRef,
            serverQueue,
            serverChannelSemaphore,
            serverChannelsRef,
            clientChannelsRef
          )
          _ <- peerGroup.initialize()
        } yield peerGroup
      }(_.shutdown())
    }

  // Separate resource so if the server initialization fails, this still gets shut down.
  private val makeEventLoop =
    Resource.make {
      Task(new NioEventLoopGroup())
    } { group =>
      toTask(group.shutdownGracefully())
    }

  private class ChannelImpl[M](
      nettyChannel: io.netty.channel.Channel,
      localAddress: InetSocketAddress,
      remoteAddress: InetSocketAddress,
      messageQueue: CloseableQueue[ChannelEvent[M]],
      isClosedRef: Ref[Task, Boolean],
      role: ChannelImpl.Role
  )(implicit codec: Codec[M], scheduler: Scheduler)
      extends Channel[InetMultiAddress, M]
      with StrictLogging {

    protected override val s = scheduler

    override val to =
      InetMultiAddress(remoteAddress)

    override def nextMessage() =
      messageQueue.next()

    private val raiseIfClosed =
      isClosedRef.get.ifM(
        Task.raiseError(
          new ChannelAlreadyClosedException[InetMultiAddress](InetMultiAddress(localAddress), to)
        ),
        Task.unit
      )

    override def sendMessage(message: M) =
      for {
        _ <- raiseIfClosed
        _ <- Task(
          logger.debug(s"Sending $role message ${message.toString.take(100)}... from $localAddress to $remoteAddress")
        )
        encodedMessage <- Task.fromTry(codec.encode(message).toTry)
        asBuffer = encodedMessage.toByteBuffer
        packet = new DatagramPacket(Unpooled.wrappedBuffer(asBuffer), remoteAddress, localAddress)
        _ <- toTask(nettyChannel.writeAndFlush(packet)).onErrorRecoverWith {
          case _: IOException =>
            Task.raiseError(new MessageMTUException[InetMultiAddress](to, asBuffer.capacity))
        }
      } yield ()

    def handleMessage(maybeMessage: Attempt[M]): Task[Unit] = {
      isClosedRef.get.ifM(
        Task.unit,
        maybeMessage match {
          case Attempt.Successful(message) =>
            publish(MessageReceived(message))
          case Attempt.Failure(err) =>
            publish(DecodingError)
        }
      )
    }

    def handleError(error: Throwable): Task[Unit] =
      isClosedRef.get.ifM(
        Task.unit,
        publish(UnexpectedError(error))
      )

    private def close() =
      for {
        _ <- raiseIfClosed
        _ <- isClosedRef.set(true)
        // Initiated by the consumer, so discard messages.
        _ <- messageQueue.close(discard = true)
      } yield ()

    private def publish(event: ChannelEvent[M]): Task[Unit] =
      messageQueue.tryOffer(event).void
  }

  private object ChannelImpl {
    sealed trait Role {
      override def toString(): String = this match {
        case Server => "server"
        case Client => "client"
      }
    }
    object Server extends Role
    object Client extends Role

    def apply[M: Codec](
        nettyChannel: io.netty.channel.Channel,
        localAddress: InetSocketAddress,
        remoteAddress: InetSocketAddress,
        role: Role,
        capacity: Int
    )(implicit scheduler: Scheduler): Resource[Task, ChannelImpl[M]] =
      Resource.make {
        for {
          isClosedRef <- Ref[Task].of(false)
          // The publishing of messages happens asynchronously in this class,
          // so there can be multiple publications going on at the same time.
          messageQueue <- CloseableQueue[ChannelEvent[M]](capacity, ChannelType.MPMC)
          channel = new ChannelImpl[M](
            nettyChannel,
            localAddress,
            remoteAddress,
            messageQueue,
            isClosedRef,
            role
          )
        } yield channel
      }(_.close())
  }
}
