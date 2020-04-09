package io.iohk.scalanet.peergroup.dynamictls

import java.io.IOException
import java.net.{ConnectException, InetSocketAddress}
import java.nio.channels.ClosedChannelException

import io.iohk.scalanet.codec.StreamCodec
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.toTask
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelBrokenException, HandshakeException, ServerEvent}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.PeerInfo
import io.iohk.scalanet.peergroup.{Channel, InetMultiAddress, PeerGroup}
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelInitializer}
import io.netty.handler.ssl.{SslContext, SslHandshakeCompletionEvent}
import javax.net.ssl.{SSLException, SSLHandshakeException, SSLKeyException}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.observables.ConnectableObservable
import org.slf4j.LoggerFactory
import scodec.bits.BitVector

import scala.collection.JavaConverters._
import scala.concurrent.Promise

private[dynamictls] object DynamicTLSPeerGroupInternals {
  implicit class ChannelOps(val channel: io.netty.channel.Channel) {
    def sendMessage[M](m: M)(implicit codec: StreamCodec[M]): Task[Unit] =
      for {
        enc <- Task.fromTry(codec.encode(m).toTry)
        _ <- toTask(channel.writeAndFlush(Unpooled.wrappedBuffer(enc.toByteBuffer)))
      } yield ()
  }

  class MessageNotifier[M](
      val messageSubject: ConnectableSubject[M],
      codec: StreamCodec[M]
  ) extends ChannelInboundHandlerAdapter {

    private val log = LoggerFactory.getLogger(getClass)

    override def channelInactive(channelHandlerContext: ChannelHandlerContext): Unit = {
      log.debug("Channel to peer {} inactive", channelHandlerContext.channel().remoteAddress())
      messageSubject.onComplete()
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val byteBuf = msg.asInstanceOf[ByteBuf]
      try {
        log.info(
          s"Processing inbound message from remote address ${ctx.channel().remoteAddress()} " +
            s"to local address ${ctx.channel().localAddress()}"
        )
        codec.streamDecode(BitVector(byteBuf.nioBuffer())) match {
          case Left(value) =>
            log.debug("Unexpected decoding error {} from peer {}", value: Any, ctx.channel().remoteAddress(): Any)

          case Right(value) =>
            log.debug("Decoded {} messages from peer {}", value.size, ctx.channel().remoteAddress())
            value.foreach(messageSubject.onNext)
        }
      } finally {
        byteBuf.release()
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      // swallow netty's default logging of the stack trace.
      log.debug("Unexpected exception {} on channel to peer {}", cause.getMessage: Any, ctx.channel().remoteAddress(): Any)
    }
  }

  class ClientChannelImpl[M](
      peerInfo: PeerInfo,
      clientBootstrap: Bootstrap,
      sslClientCtx: SslContext,
      codec: StreamCodec[M]
  )(implicit scheduler: Scheduler)
      extends Channel[PeerInfo, M] {

    private val log = LoggerFactory.getLogger(getClass)

    val to: PeerInfo = peerInfo

    private val activation = Promise[io.netty.channel.Channel]()
    private val activationF = activation.future

    private val deactivation = Promise[Unit]()
    private val deactivationF = deactivation.future

    private val messageSubject = ConnectableSubject[M]()

    private val bootstrap: Bootstrap = clientBootstrap
      .clone()
      .handler(new ChannelInitializer[SocketChannel]() {
        def initChannel(ch: SocketChannel): Unit = {
          log.debug("Initiating connection to peer {}", peerInfo)
          val pipeline = ch.pipeline()
          val sslHandler = sslClientCtx.newHandler(ch.alloc())

          pipeline
            .addLast("ssl", sslHandler) //This needs to be first
            .addLast(new ChannelInboundHandlerAdapter() {

              override def channelInactive(ctx: ChannelHandlerContext): Unit = {
                deactivation.success(())
              }

              override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
                evt match {
                  case e: SslHandshakeCompletionEvent =>
                    log.info(
                      s"Ssl Handshake client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
                    )
                    if (e.isSuccess) {
                      log.debug("Handshake to peer {} succeeded", peerInfo)
                      activation.success(ctx.channel())
                    } else {
                      log.debug("Handshake to peer {} failed due to {}", peerInfo, e: Any)
                      activation.failure(e.cause())
                    }

                  case _ =>
                    log.info(
                      s"User Event client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
                    )
                }
              }
            })
            .addLast(new MessageNotifier[M](messageSubject, codec))
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

    def initialize: Task[ClientChannelImpl[M]] = {
      val connectTask = for {
        _ <- Task.now(log.debug("Initiating connection to peer {}", peerInfo))
        _ <- toTask(bootstrap.connect(peerInfo.address.inetSocketAddress))
        _ <- Task.fromFuture(activationF)
        _ <- Task.now(log.debug("Connection to peer {} finished successfully", peerInfo))
      } yield this

      connectTask.onErrorRecoverWith {
        case t: Throwable =>
          Task.raiseError(mapException(t))
      }
    }

    override def sendMessage(message: M): Task[Unit] = {
      val sendTask = for {
        channel <- Task.fromFuture(activationF)
        _ <- Task.now(
          log.debug(
            s"Processing outbound message from local address ${channel.localAddress()} " +
              s"to remote address ${channel.remoteAddress()} via channel id ${channel.id()}"
          )
        )
        _ <- channel.sendMessage(message)(codec)
      } yield ()

      sendTask.onErrorRecoverWith {
        case e: IOException =>
          log.debug("Sending message to {} failed due to {}", peerInfo: Any, e: Any)
          Task.raiseError(new ChannelBrokenException[PeerInfo](to, e))
      }
    }

    override def in: ConnectableObservable[M] = messageSubject

    override def close(): Task[Unit] = {
      for {
        _ <- Task.now(log.debug("Closing client channel to peer {}", peerInfo))
        _ <- Task(messageSubject.onComplete())
        ctx <- Task.fromFuture(activationF)
        _ <- toTask(ctx.close())
        _ <- Task.fromFuture(deactivationF)
        _ <- Task.now(log.debug("Client channel to peer {} closed", peerInfo))
      } yield ()
    }
  }

  class ServerChannelBuilder[M](
      serverSubject: ConnectableSubject[ServerEvent[PeerInfo, M]],
      val nettyChannel: SocketChannel,
      sslServerCtx: SslContext,
      codec: StreamCodec[M]
  )(implicit scheduler: Scheduler) {
    private val log = LoggerFactory.getLogger(getClass)
    val sslHandler = sslServerCtx.newHandler(nettyChannel.alloc())

    val messageSubject = ConnectableSubject[M]()
    val sslEngine = sslHandler.engine()

    nettyChannel
      .pipeline()
      .addLast("ssl", sslHandler) //This needs to be first
      .addLast(new ChannelInboundHandlerAdapter() {
        override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
          evt match {
            case e: SslHandshakeCompletionEvent =>
              val localAddress = InetMultiAddress(ctx.channel().localAddress().asInstanceOf[InetSocketAddress])
              val remoteAddress = InetMultiAddress(ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress])
              if (e.isSuccess) {
                // Hacky way of getting id of incoming peer from ssl engine. Only works because we have new engine for
                // each connection
                val params = sslEngine.getSSLParameters.getServerNames.asScala.head
                val peerId = BitVector(params.getEncoded)
                log.debug(
                  s"Ssl Handshake server channel from $localAddress " +
                    s"to $remoteAddress with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
                )
                val channel = new ServerChannelImpl[M](nettyChannel, peerId, codec, messageSubject)
                serverSubject.onNext(ChannelCreated(channel))
              } else {
                log.debug("Ssl handshake failed from peer with address {}", remoteAddress)
                // Handshake failed we do not have id of remote peer
                serverSubject
                  .onNext(
                    PeerGroup.ServerEvent
                      .HandshakeFailed(new HandshakeException(PeerInfo(BitVector.empty, remoteAddress), e.cause()))
                  )
              }
          }
        }
      })
      .addLast(new MessageNotifier(messageSubject, codec))
  }

  class ServerChannelImpl[M](
      val nettyChannel: SocketChannel,
      peerId: BitVector,
      codec: StreamCodec[M],
      messageSubject: ConnectableSubject[M]
  )(implicit scheduler: Scheduler)
      extends Channel[PeerInfo, M] {

    private val log = LoggerFactory.getLogger(getClass)

    log.debug(
      s"Creating server channel from ${nettyChannel.localAddress()} to ${nettyChannel.remoteAddress()} with channel id ${nettyChannel.id}"
    )

    override val to: PeerInfo = PeerInfo(peerId, InetMultiAddress(nettyChannel.remoteAddress()))

    override def sendMessage(message: M): Task[Unit] = {
      log.debug("Sending message to peer {} via server channel", nettyChannel.localAddress())
      nettyChannel.sendMessage(message)(codec).onErrorRecoverWith {
        case e: IOException =>
          log.debug("Sending message to {} failed due to {}", message, e)
          Task.raiseError(new ChannelBrokenException[PeerInfo](to, e))
      }
    }

    override def in: ConnectableObservable[M] = messageSubject

    override def close(): Task[Unit] =
      for {
        _ <- Task.now(log.debug("Closing client channel to peer {}", to))
        _ <- Task(messageSubject.onComplete())
        _ <- toTask(nettyChannel.close())
        _ <- Task.now(log.debug("Server channel to peer {} closed", to))
      } yield ()
  }

}
