package io.iohk.scalanet.peergroup

import io.iohk.decco._
import io.iohk.scalanet.peergroup.SimplestPeerGroup.{Config, ControlMessage}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

/**
  * Trivial example of a higher-level peer group.
  * Demonstrates the mapping of addresses and messages to an underlying transport
  * where control messages may be sent in addition to those from the user.
  * There is no enrollment process. Instances are configured with a static table of all known peers.
  */
class SimplestPeerGroup[A, AA, M](
                                   val config: Config[A, AA],
                                   underLyingPeerGroup: PeerGroup[AA, Either[ControlMessage[A, AA], M]]
                                 )(
                                   implicit aCodec: Codec[A],
                                   aaCodec: Codec[AA],
                                   scheduler: Scheduler
                                 ) extends PeerGroup[A, M] {

  private val log = LoggerFactory.getLogger(getClass)

  private implicit val apc: PartialCodec[A] = aCodec.partialCodec
  private implicit val aapc: PartialCodec[AA] = aaCodec.partialCodec

  override def processAddress: A = config.processAddress

  override def client(to: A): Task[Channel[A, M]] =
    underLyingPeerGroup.client(config.knownPeers(to)).map { underlyingChannel =>
      new ChannelImpl(to, underlyingChannel)
    }

  override def server(): Observable[Channel[A, M]] = {
    underLyingPeerGroup.server().map { underlyingChannel =>
      val reverseLookup: Map[AA, A] = config.knownPeers.map(_.swap)
      new ChannelImpl(reverseLookup(underlyingChannel.to), underlyingChannel)
    }
  }

  override def shutdown(): Task[Unit] = underLyingPeerGroup.shutdown()

  override def initialize(): Task[Unit] = {
    Task.unit
  }

  private class ChannelImpl(val to: A, underlyingChannel: Channel[AA, Either[ControlMessage[A, AA], M]])
    extends Channel[A, M] {

    override def sendMessage(message: M): Task[Unit] = {
      underlyingChannel.sendMessage(Right(message))
    }

    override def in: Observable[M] = {
      underlyingChannel.in.collect {
        case Right(message) =>
          log.debug(s"Processing inbound message from remote address $to to local address $processAddress, $message")
          message
      }
    }

    override def close(): Task[Unit] =
      underlyingChannel.close()
  }
}

object SimplestPeerGroup {

  sealed trait ControlMessage[A, AA]

  // Not used. Included because codec derivation does not work for empty sealed traits.
  case class CM1[A, AA]() extends ControlMessage[A, AA]

  case class Config[A, AA](processAddress: A, knownPeers: Map[A, AA])
}
