package io.iohk.scalanet.peergroup.dynamictls

import java.io.IOException
import java.net.{ConnectException, InetSocketAddress}
import java.nio.channels.ClosedChannelException
import com.typesafe.scalalogging.StrictLogging
import io.iohk.scalanet.peergroup.Channel.{ChannelEvent, DecodingError, MessageReceived, UnexpectedError}
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.ProxySupport.Socks5Config
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelBrokenException, HandshakeException, ServerEvent}
import io.iohk.scalanet.peergroup.dynamictls.CustomHandlers.ThrottlingIpFilter
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.{FramingConfig, PeerInfo}
import io.iohk.scalanet.peergroup.{Channel, CloseableQueue, InetMultiAddress, PeerGroup}
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelInitializer, EventLoop}
import io.netty.handler.ssl.{SslContext, SslHandshakeCompletionEvent}

import javax.net.ssl.{SSLException, SSLHandshakeException, SSLKeyException}
import monix.eval.Task
import monix.execution.{ChannelType, Scheduler}
import scodec.bits.BitVector

import scala.concurrent.Promise
import scala.util.control.NonFatal
import io.netty.channel.EventLoop
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, LengthFieldPrepender, TooLongFrameException}
import io.netty.handler.proxy.Socks5ProxyHandler
import scodec.Attempt.{Failure, Successful}
import scodec.Codec

private[dynamictls] object DynamicTLSPeerGroupInternals {
  def buildFramingCodecs(config: FramingConfig): (LengthFieldBasedFrameDecoder, LengthFieldPrepender) = {
    val encoder = new LengthFieldPrepender(
      config.byteOrder,
      config.lengthFieldLength,
      config.encodingLengthAdjustment,
      config.lengthIncludesLengthFieldLength
    )

    val decoder = new LengthFieldBasedFrameDecoder(
      config.byteOrder,
      config.maxFrameLength,
      config.lengthFieldOffset,
      config.lengthFieldLength,
      config.decodingLengthAdjustment,
      config.initialBytesToStrip,
      config.failFast
    )

    (decoder, encoder)
  }

  implicit class ChannelOps(val channel: io.netty.channel.Channel) {
    def sendMessage[M](m: M)(implicit codec: Codec[M]): Task[Unit] =
      for {
        enc <- Task.fromTry(codec.encode(m).toTry)
        _ <- toTask(channel.writeAndFlush(Unpooled.wrappedBuffer(enc.toByteBuffer)))
      } yield ()
  }

  class MessageNotifier[M](
      messageQueue: CloseableQueue[ChannelEvent[M]],
      codec: Codec[M],
      eventLoop: EventLoop
  ) extends ChannelInboundHandlerAdapter
      with StrictLogging {

    // This should be a single threaded scheduler, so as long as we use `runAsyncAndForget` it shouldn't change the message ordering.
    val scheduler = Scheduler(eventLoop)

    override def channelInactive(channelHandlerContext: ChannelHandlerContext): Unit = {
      logger.debug("Channel to peer {} inactive", channelHandlerContext.channel().remoteAddress())
      executeAsync(messageQueue.close(discard = false))
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val byteBuf = msg.asInstanceOf[ByteBuf]
      try {
        codec.decodeValue(BitVector(byteBuf.nioBuffer())) match {
          case Successful(message) =>
            handleEvent(MessageReceived(message))
          case Failure(ex) =>
            logger.error("Unexpected decoding error {} from peer {}", ex.message, ctx.channel().remoteAddress(): Any)
            handleEvent(DecodingError)
        }
      } catch {
        case NonFatal(e) =>
          handleEvent(UnexpectedError(e))
      } finally {
        byteBuf.release()
        ()
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      cause match {
        case e: TooLongFrameException =>
          logger.error("Too long frame {} on channel to peer {}", e.getMessage, ctx.channel().remoteAddress())
          handleEvent(DecodingError)
        case e =>
          // swallow netty's default logging of the stack trace.
          logger.error(
            "Unexpected exception {} on channel to peer {}",
            cause.getMessage: Any,
            ctx.channel().remoteAddress(): Any
          )
          handleEvent(UnexpectedError(cause))
      }
    }

    private def handleEvent(event: ChannelEvent[M]): Unit =
      // Don't want to lose message, so `offer`, not `tryOffer`.
      executeAsync(messageQueue.offer(event).void)

    private def executeAsync(task: Task[Unit]): Unit =
      task.runAsyncAndForget(scheduler)
  }

  class ClientChannelImpl[M](
      peerInfo: PeerInfo,
      clientBootstrap: Bootstrap,
      sslClientCtx: SslContext,
      framingConfig: DynamicTLSPeerGroup.FramingConfig,
      socks5Config: Option[Socks5Config]
  )(implicit scheduler: Scheduler, codec: Codec[M])
      extends Channel[PeerInfo, M]
      with StrictLogging {
    val (decoder, encoder) = buildFramingCodecs(framingConfig)

    val to: PeerInfo = peerInfo

    private val activation = Promise[io.netty.channel.Channel]()
    private val activationF = activation.future

    private val messageQueue = makeMessageQueue[M]

    private val bootstrap: Bootstrap = clientBootstrap
      .clone()
      .handler(new ChannelInitializer[SocketChannel]() {
        def initChannel(ch: SocketChannel): Unit = {
          logger.debug("Initiating connection to peer {}", peerInfo)
          val pipeline = ch.pipeline()
          val sslHandler = sslClientCtx.newHandler(ch.alloc())

          socks5Config.foreach { config =>
            val sock5Proxy = config.authConfig.fold(new Socks5ProxyHandler(config.proxyAddress)) { authConfig =>
              new Socks5ProxyHandler(config.proxyAddress, authConfig.user, authConfig.password)
            }
            pipeline.addLast(sock5Proxy)
          }

          pipeline
            .addLast("ssl", sslHandler) //This needs to be first
            .addLast(new ChannelInboundHandlerAdapter() {
              override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
                evt match {
                  case e: SslHandshakeCompletionEvent =>
                    logger.info(
                      s"Ssl Handshake client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
                    )
                    if (e.isSuccess) {
                      logger.debug("Handshake to peer {} succeeded", peerInfo)
                      activation.success(ctx.channel())
                    } else {
                      logger.debug("Handshake to peer {} failed due to {}", peerInfo, e: Any)
                      activation.failure(e.cause())
                    }

                  case ev =>
                    logger.debug(
                      s"User Event $ev on client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
                    )
                }
              }
            })
            .addLast(encoder)
            .addLast(decoder)
            .addLast(new MessageNotifier[M](messageQueue, codec, ch.eventLoop))
          ()
        }
      })

    private def mapException(t: Throwable): Throwable = t match {
      case _: ClosedChannelException =>
        new PeerGroup.ChannelBrokenException(to, t)
      case _: ConnectException =>
        new PeerGroup.ChannelSetupException(to, t)
      case _: SSLKeyException =>
        new PeerGroup.HandshakeException(to, t)
      case _: SSLHandshakeException =>
        new PeerGroup.HandshakeException(to, t)
      case _: SSLException =>
        new PeerGroup.HandshakeException(to, t)
      case _ =>
        t
    }

    private[dynamictls] def initialize: Task[ClientChannelImpl[M]] = {
      val connectTask = for {
        _ <- Task(logger.debug("Initiating connection to peer {}", peerInfo))
        _ <- toTask(bootstrap.connect(peerInfo.address.inetSocketAddress))
        _ <- Task.fromFuture(activationF)
        _ <- Task(logger.debug("Connection to peer {} finished successfully", peerInfo))
      } yield this

      connectTask.onErrorRecoverWith {
        case t: Throwable =>
          Task.raiseError(mapException(t))
      }
    }

    override def sendMessage(message: M): Task[Unit] = {
      val sendTask = for {
        channel <- Task.fromFuture(activationF)
        _ <- Task(
          logger.debug(
            s"Processing outbound message from local address ${channel.localAddress()} " +
              s"to remote address ${channel.remoteAddress()} via channel id ${channel.id()}"
          )
        )
        _ <- channel.sendMessage(message)(codec)
      } yield ()

      sendTask.onErrorRecoverWith {
        case e: IOException =>
          Task(logger.debug("Sending message to {} failed due to {}", peerInfo: Any, e: Any)) *>
            Task.raiseError(new ChannelBrokenException[PeerInfo](to, e))
      }
    }

    override def nextChannelEvent =
      messageQueue.next

    /**
      * To be sure that `channelInactive` had run before returning from close, we are also waiting for ctx.closeFuture() after
      * ctx.close()
      */
    private[dynamictls] def close(): Task[Unit] = {
      for {
        _ <- Task(logger.debug("Closing client channel to peer {}", peerInfo))
        ctx <- Task.fromFuture(activationF)
        _ <- toTask(ctx.close())
        _ <- toTask(ctx.closeFuture())
        _ <- messageQueue.close(discard = true).attempt
        _ <- Task(logger.debug("Client channel to peer {} closed", peerInfo))
      } yield ()
    }
  }

  class ServerChannelBuilder[M](
      serverQueue: CloseableQueue[ServerEvent[PeerInfo, M]],
      val nettyChannel: SocketChannel,
      sslServerCtx: SslContext,
      framingConfig: DynamicTLSPeerGroup.FramingConfig,
      throttlingIpFilter: Option[ThrottlingIpFilter]
  )(implicit scheduler: Scheduler, codec: Codec[M])
      extends StrictLogging {
    val sslHandler = sslServerCtx.newHandler(nettyChannel.alloc())

    val messageQueue = makeMessageQueue[M]
    val sslEngine = sslHandler.engine()

    val pipeLine = nettyChannel.pipeline()

    val (decoder, encoder) = buildFramingCodecs(framingConfig)

    // adding throttling filter as first (if configures), so if its connection from address which breaks throttling rules
    // it will be closed immediately without using more resources
    throttlingIpFilter.foreach(filter => pipeLine.addLast(filter))

    pipeLine
      .addLast("ssl", sslHandler) //This needs to be first
      .addLast(new ChannelInboundHandlerAdapter() {
        override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
          evt match {
            case e: SslHandshakeCompletionEvent =>
              val localAddress = InetMultiAddress(ctx.channel().localAddress().asInstanceOf[InetSocketAddress])
              val remoteAddress = InetMultiAddress(ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress])
              if (e.isSuccess) {
                // after handshake handshake session becomes session, so during handshake sslEngine.getHandshakeSession needs
                // to be called to put value in session, but after handshake sslEngine.getSession needs to be called
                // get the same session with value
                val peerId = sslEngine.getSession.getValue(DynamicTLSPeerGroupUtils.peerIdKey).asInstanceOf[BitVector]
                logger.debug(
                  s"Ssl Handshake server channel from $localAddress " +
                    s"to $remoteAddress with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
                )
                val channel = new ServerChannelImpl[M](nettyChannel, peerId, messageQueue)
                handleEvent(ChannelCreated(channel, channel.close()))
              } else {
                logger.debug("Ssl handshake failed from peer with address {}", remoteAddress)
                // Handshake failed we do not have id of remote peer
                handleEvent(
                  PeerGroup.ServerEvent
                    .HandshakeFailed(new HandshakeException(PeerInfo(BitVector.empty, remoteAddress), e.cause()))
                )
              }
            case ev =>
              logger.debug(
                s"User Event $ev on server channel from ${ctx.channel().localAddress()} " +
                  s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
              )
          }
        }
      })
      .addLast(encoder)
      .addLast(decoder)
      .addLast(new MessageNotifier(messageQueue, codec, nettyChannel.eventLoop))

    private def handleEvent(event: ServerEvent[PeerInfo, M]): Unit =
      serverQueue.offer(event).void.runSyncUnsafe()
  }

  class ServerChannelImpl[M](
      val nettyChannel: SocketChannel,
      peerId: BitVector,
      messageQueue: CloseableQueue[ChannelEvent[M]]
  )(implicit scheduler: Scheduler, codec: Codec[M])
      extends Channel[PeerInfo, M]
      with StrictLogging {

    logger.debug(
      s"Creating server channel from ${nettyChannel.localAddress()} to ${nettyChannel.remoteAddress()} with channel id ${nettyChannel.id}"
    )

    override val to: PeerInfo = PeerInfo(peerId, InetMultiAddress(nettyChannel.remoteAddress()))

    override def sendMessage(message: M): Task[Unit] = {
      logger.debug("Sending message to peer {} via server channel", nettyChannel.localAddress())
      nettyChannel.sendMessage(message)(codec).onErrorRecoverWith {
        case e: IOException =>
          logger.debug("Sending message to {} failed due to {}", message, e)
          Task.raiseError(new ChannelBrokenException[PeerInfo](to, e))
      }
    }

    override def nextChannelEvent: Task[Option[ChannelEvent[M]]] = messageQueue.next

    /**
      * To be sure that `channelInactive` had run before returning from close, we are also waiting for nettyChannel.closeFuture() after
      * nettyChannel.close()
      */
    private[dynamictls] def close(): Task[Unit] =
      for {
        _ <- Task(logger.debug("Closing server channel to peer {}", to))
        _ <- toTask(nettyChannel.close())
        _ <- toTask(nettyChannel.closeFuture())
        _ <- messageQueue.close(discard = true).attempt
        _ <- Task(logger.debug("Server channel to peer {} closed", to))
      } yield ()
  }

  private def makeMessageQueue[M](implicit scheduler: Scheduler) =
    CloseableQueue.unbounded[ChannelEvent[M]](ChannelType.SPMC).runSyncUnsafe()

}
