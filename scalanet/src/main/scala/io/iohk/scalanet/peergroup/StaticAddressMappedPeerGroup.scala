package io.iohk.scalanet.peergroup

import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import io.iohk.scalanet.peergroup.PeerGroup.{HandshakeException, ServerEvent}
import io.iohk.scalanet.peergroup.StaticAddressMappedPeerGroup.Config
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.observables.ConnectableObservable

/**
  * Higher-level peer group representing a simple, static overlay network
  * with an abstract address type.
  * @param config defines the node's processAddress and known peers.
  * @param underLyingPeerGroup any underlying transport
  * @tparam A the address type of this peer group.
  * @tparam AA the address type of the underlying peer group.
  * @tparam M the message type.
  */
class StaticAddressMappedPeerGroup[A, AA, M](
    val config: Config[A, AA],
    underLyingPeerGroup: PeerGroup[AA, M]
)(implicit scheduler: Scheduler)
    extends PeerGroup[A, M] {

  private val reverseLookup = config.knownPeers.map(_.swap)
  override def processAddress: A = config.processAddress

  override def client(to: A): Task[Channel[A, M]] =
    underLyingPeerGroup.client(config.knownPeers(to)).map { underlyingChannel =>
      new ChannelImpl(to, underlyingChannel)
    }

  private val observable = underLyingPeerGroup.server().map {
    case ChannelCreated(underlyingChannel) =>
      val a = reverseLookup(underlyingChannel.to)
      ChannelCreated[A, M](new ChannelImpl(a, underlyingChannel))
    case HandshakeFailed(failure) =>
      HandshakeFailed[A, M](new HandshakeException[A](reverseLookup(failure.to), failure.cause))
  }
  private val connectableObservable =
    ConnectableSubject[ServerEvent[A, M]](observable)

  underLyingPeerGroup.server().connect()

  override def server(): ConnectableObservable[ServerEvent[A, M]] = connectableObservable

  override def shutdown(): Task[Unit] =
    underLyingPeerGroup.shutdown()

  override def initialize(): Task[Unit] =
    Task.unit

  private class ChannelImpl(val to: A, underlyingChannel: Channel[AA, M]) extends Channel[A, M] {

    override def sendMessage(message: M): Task[Unit] =
      underlyingChannel.sendMessage(message)

    override def in: ConnectableObservable[M] = underlyingChannel.in

    override def close(): Task[Unit] =
      underlyingChannel.close()
  }

}

object StaticAddressMappedPeerGroup {
  case class Config[A, AA](processAddress: A, knownPeers: Map[A, AA])
}
