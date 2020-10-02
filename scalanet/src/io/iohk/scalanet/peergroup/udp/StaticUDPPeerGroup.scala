package io.iohk.scalanet.peergroup.udp

import cats.effect.concurrent.Ref
import cats.effect.Resource
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.{Channel, Release}
import io.iohk.scalanet.peergroup.{InetMultiAddress}
import io.iohk.scalanet.peergroup.Channel.{ChannelEvent, MessageReceived, DecodingError}
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.{ServerEvent, TerminalPeerGroup, MessageMTUException}
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
import monix.execution.Scheduler
import scala.util.control.NonFatal
import scodec.{Codec, Attempt}
import scodec.bits.BitVector
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.Channel.UnexpectedError
import monix.execution.schedulers.CanBlock

/**
  * PeerGroup implementation on top of UDP that uses the same local port
  * when creating channels to remote addresses as the one it listens on
  * for incoming messages. This makes it compatible with certain UDP
  * based node discovery protocols. It also means that incoming messages
  * cannot be tied to a specific channel, so if multiple channels are
  * open to the same remote address, they will all see the same messages.
  *
  * @param config bind address etc. See the companion object.
  * @param codec a scodec codec for reading writing messages to NIO ByteBuffer.
  * @tparam M the message type.
  */
class StaticUDPPeerGroup[M] private (
    config: StaticUDPPeerGroup.Config,
    workerGroup: NioEventLoopGroup,
    isShutdownRef: Ref[Task, Boolean],
    serverSubject: ConnectableSubject[ServerEvent[InetMultiAddress, M]],
    serverChannelsRef: Ref[Task, Map[InetSocketAddress, StaticUDPPeerGroup.ChannelRelease[M]]],
    clientChannelsRef: Ref[Task, Map[InetSocketAddress, Set[StaticUDPPeerGroup.ChannelRelease[M]]]]
)(implicit scheduler: Scheduler, codec: Codec[M])
    extends TerminalPeerGroup[InetMultiAddress, M]
    with StrictLogging {

  import StaticUDPPeerGroup.{ChannelImpl, ChannelRelease, TaskExecutionOps}

  override val processAddress = config.processAddress
  override val server = serverSubject

  private def raiseIfShutdown =
    isShutdownRef.get
      .ifM(Task.raiseError(new IllegalStateException("The peer group has already been shut down.")), Task.unit)

  /** Create a new channel from the local server port to the remote address. */
  override def client(to: InetMultiAddress): Resource[Task, Channel[InetMultiAddress, M]] = {
    for {
      _ <- Resource.liftF(raiseIfShutdown)
      channel <- Resource {
        ChannelImpl[M](
          nettyChannel = serverBinding.channel,
          localAddress = config.bindAddress,
          remoteAddress = to.inetSocketAddress
        ).allocated.flatMap {
          case (channel, release) =>
            // Register the channel as belonging to the remote address so that
            // we can replicate incoming messages to it later.
            val add = for {
              _ <- addClientChannel(channel -> release)
              _ <- Task(logger.debug(s"Added UDP client channel to $to"))
            } yield ()

            val remove = for {
              _ <- removeClientChannel(channel -> release)
              _ <- release
              _ <- Task(logger.debug(s"Removed UDP client channel to $to"))
            } yield ()

            add.as(channel -> remove)
        }
      }
    } yield channel
  }

  private def addClientChannel(channel: ChannelRelease[M]) =
    clientChannelsRef.update { clientChannels =>
      val remoteAddress = channel._1.to.inetSocketAddress
      val current = clientChannels.getOrElse(remoteAddress, Set.empty)
      clientChannels.updated(remoteAddress, current + channel)
    }

  private def removeClientChannel(channel: ChannelRelease[M]) =
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
        // All incoming messages are handled on the same event loop, by the same channel,
        // so we don't have to synchronize anything, it will only try to create one channel at a time.
        ChannelImpl[M](
          nettyChannel = serverBinding.channel,
          localAddress = config.bindAddress,
          remoteAddress = remoteAddress
        ).allocated.flatMap {
          case (channel, release) =>
            val remove = for {
              _ <- serverChannelsRef.update(_ - remoteAddress)
              _ <- release
              _ <- Task(logger.debug(s"Removed UDP server channel from $remoteAddress"))
            } yield ()

            val add = for {
              _ <- serverChannelsRef.update(_.updated(remoteAddress, channel -> release))
              _ <- Task(serverSubject.onNext(ChannelCreated(channel, remove)))
              _ <- Task(logger.debug(s"Added UDP server channel from $remoteAddress"))
            } yield channel

            add.as(channel)
        }
    }
  }

  private def getClientChannels(remoteAddress: InetSocketAddress): Task[Iterable[ChannelImpl[M]]] =
    clientChannelsRef.get.map {
      _.getOrElse(remoteAddress, Set.empty).toIterable.map(_._1)
    }

  private def replicateToChannels(remoteAddress: InetSocketAddress)(f: ChannelImpl[M] => Task[Unit]): Task[Unit] =
    isShutdownRef.get.ifM(
      Task.unit,
      for {
        serverChannel <- getOrCreateServerChannel(remoteAddress)
        clientChannels <- getClientChannels(remoteAddress)
        channels = Iterable(serverChannel) ++ clientChannels
        _ <- Task.parTraverseUnordered(channels)(f)
      } yield ()
    )

  /** Replicate the incoming message to the server channel and all client channels connected to the remote address. */
  private def handleMessage(remoteAddress: InetSocketAddress, maybeMessage: Attempt[M]): Task[Unit] =
    replicateToChannels(remoteAddress)(_.handleMessage(maybeMessage))

  private def handleError(remoteAddress: InetSocketAddress, error: Throwable): Task[Unit] =
    replicateToChannels(remoteAddress)(_.handleError(error))

  private def tryDecodeDatagram(datagram: DatagramPacket): Attempt[M] =
    codec.decodeValue(BitVector(datagram.content.nioBuffer))

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
                  logger.debug(s"Server channel read message from $remoteAddress")
                  handleMessage(remoteAddress, tryDecodeDatagram(datagram)).executeOn(ctx)
                } catch {
                  case NonFatal(ex) =>
                    handleError(remoteAddress, ex).executeOn(ctx)
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
                    handleError(remoteAddress, ex).executeOn(ctx)
                }
              }
            })

          ()
        }
      })
      .bind(config.bindAddress)

  // Wait until the server is bound.
  private def initialize(): Task[Unit] =
    raiseIfShutdown >>
      toTask(serverBinding).onErrorRecoverWith {
        case NonFatal(ex) =>
          Task.raiseError(InitializationError(ex.getMessage, ex.getCause))
      } >> Task(logger.info(s"Server bound to address ${config.bindAddress}"))

  private def shutdown(): Task[Unit] = {
    for {
      _ <- Task(logger.info(s"Shutting down UDP peer group for peer ${config.processAddress}"))
      // Mark the group as shutting down to stop accepting incoming connections.
      _ <- isShutdownRef.set(true)
      _ <- Task(serverSubject.onComplete())
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
      processAddress: InetMultiAddress
  )
  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, InetMultiAddress(bindAddress))
  }

  private type ChannelRelease[M] = (ChannelImpl[M], Release)

  def apply[M: Codec](config: Config)(implicit scheduler: Scheduler): Resource[Task, StaticUDPPeerGroup[M]] =
    makeEventLoop.flatMap { workerGroup =>
      Resource.make {
        for {
          isShutdownRef <- Ref[Task].of(false)
          clientChannelsRef <- Ref[Task].of(Map.empty[InetSocketAddress, Set[ChannelRelease[M]]])
          serverChannelsRef <- Ref[Task].of(Map.empty[InetSocketAddress, ChannelRelease[M]])
          serverSubject = ConnectableSubject[ServerEvent[InetMultiAddress, M]]()
          peerGroup = new StaticUDPPeerGroup[M](
            config,
            workerGroup,
            isShutdownRef,
            serverSubject,
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
      messageSubject: ConnectableSubject[ChannelEvent[M]],
      isClosedRef: Ref[Task, Boolean]
  )(implicit codec: Codec[M])
      extends Channel[InetMultiAddress, M]
      with StrictLogging {
    override val to = InetMultiAddress(remoteAddress)
    override val in = messageSubject

    override def sendMessage(message: M) =
      for {
        _ <- isClosedRef.get.ifM(Task.raiseError(new IllegalStateException("Channel is already closed.")), Task.unit)
        _ <- Task(logger.debug(s"Sending message ${message} to peer ${remoteAddress}"))
        encodedMessage <- Task.fromTry(codec.encode(message).toTry)
        asBuffer = encodedMessage.toByteBuffer
        packet = new DatagramPacket(Unpooled.wrappedBuffer(asBuffer), remoteAddress, localAddress)
        _ <- toTask(nettyChannel.writeAndFlush(packet)).onErrorRecoverWith {
          case _: IOException =>
            Task.raiseError(new MessageMTUException[InetMultiAddress](to, asBuffer.capacity))
        }
      } yield ()

    def handleMessage(maybeMessage: Attempt[M]): Task[Unit] =
      isClosedRef.get.ifM(
        Task.unit,
        maybeMessage match {
          case Attempt.Successful(message) =>
            Task.deferFuture(messageSubject.onNext(MessageReceived(message))).void
          case Attempt.Failure(err) =>
            Task(logger.debug(s"Message decoding failed due to ${err}", err)) >>
              Task.deferFuture(messageSubject.onNext(DecodingError)).void
        }
      )

    def handleError(error: Throwable): Task[Unit] =
      isClosedRef.get.ifM(
        Task.unit,
        Task.deferFuture(messageSubject.onNext(UnexpectedError(error))).void
      )

    private def close() =
      for {
        _ <- isClosedRef.set(true)
        _ = messageSubject.onComplete()
      } yield ()
  }

  private object ChannelImpl {
    def apply[M: Codec](
        nettyChannel: io.netty.channel.Channel,
        localAddress: InetSocketAddress,
        remoteAddress: InetSocketAddress
    )(implicit scheduler: Scheduler): Resource[Task, ChannelImpl[M]] =
      Resource.make {
        for {
          isClosedRef <- Ref[Task].of(false)
          channel = new ChannelImpl[M](
            nettyChannel,
            localAddress,
            remoteAddress,
            ConnectableSubject[ChannelEvent[M]](),
            isClosedRef
          )
        } yield channel
      }(_.close())
  }

  private implicit class TaskExecutionOps(task: Task[Unit]) {

    /** Execute a task synchronously on the channel's event loop.
      * Since we know we only have once channel this will always
      * just execute one thing at a time.
      */
    def executeOn(ctx: ChannelHandlerContext): Unit =
      task.runSyncUnsafe()(Scheduler(ctx.channel.eventLoop), implicitly[CanBlock])
  }
}
