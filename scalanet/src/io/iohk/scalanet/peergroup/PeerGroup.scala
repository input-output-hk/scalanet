package io.iohk.scalanet.peergroup

import cats.effect.Resource
import io.iohk.scalanet.peergroup.Channel.ChannelEvent
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent
import monix.eval.Task
import monix.reactive.Observable
import scodec.Codec

/**
  * A Channel represents a route between two peers on the network for the purposes
  * of sending and receiving messages. While logically the channel exists between
  * nodes, in practice it is represented by two instances of Channel, one for each
  * node respectively.
  *
  * An example channel is a TCP connection.
  *
  * Channel implementations should in general be multiplexed. This means they have the
  * property that two distinct channels represent distinct streams of
  * messages. A message sent down one channel should not cross over into another.
  *
  * @tparam A the address type
  * @tparam M the message type
  */
trait Channel[A, M] {

  /**
    * The remote address from this nodes point of view.
    */
  def to: A

  /**
    * Send a typed message down the channel.
    *
    * @param message the message to send
    * @return Where the underlying technology supports ACKs, implementations
    *         should wait for the ack and return a successful or unsuccessful Task accordingly.
    *         Where the underlying technology does not support ACKs (such as datagram protocols)
    *         implementations may return immediately with a successful Task.
    */
  def sendMessage(message: M): Task[Unit]

  /**
    * Consume the next message from the underlying event queue.
    *
    * @return The next incoming message, or None if the channel was closed.
    */
  def nextMessage(): Task[Option[ChannelEvent[M]]]
}

object Channel {
  sealed abstract class ChannelEvent[+M]
  final case class MessageReceived[M](m: M) extends ChannelEvent[M]
  final case class UnexpectedError(e: Throwable) extends ChannelEvent[Nothing]
  final case object DecodingError extends ChannelEvent[Nothing]
}

/**
  * A PeerGroup instance represents a node's view of a group of peers that can share data.
  * Concrete instances represent concrete network protocols. This can be internet protocols
  * such as TCP and UDP or higher-level peer-to-peer protocols.
  *
  * Depending on the implementation, peer groups may implement quite complex discovery, enrolment,
  * routing, encryption, relaying, etc. Alternatively, they might call directly into an existing
  * protocol stack provided by the JVM or OS. In either case, they still providing the same messaging
  * interface to calling code. Thus, while the messaging is consistent, there is expected to be a lot
  * of heterogeneity in the configuration of peer groups.
  *
  * @tparam A the address type. This type is completely arbitrary and left to peer group implementers.
  *           For an internet protocol it may be some kind of socket address (such as InetSocketAddress)
  *           or for a p2p protocols it might be a nodeId or public key hash.
  * @tparam M the message type. Currently, each peer group instance represents a single message
  *           type (or base trait). Multiple protocols, represented as multiple base traits
  *           are not yet supported, though expect this soonish.
  */
trait PeerGroup[A, M] {

  /**
    * Each PeerGroup instance can be thought as a gateway to the logical group of peers that the PeerGroup
    * represent.
    * The `processAddress` method returns the address of the self peer within the group. This is the address
    * that can be used to interact one-on-one with this peer.
    *
    * @return the current peer address.
    */
  def processAddress: A

  /**
    * This method builds a communication channel for the current peer to communicate messages with the
    * desired address,
    *
    * @param to the address of the entity that would receive our messages. Note that this address can
    *           be the address to refer to a single peer as well as a multicast address (to refer to a
    *           set of peers).
    * @return the channel to interact with the desired peer(s).
    */
  def client(to: A): Resource[Task, Channel[A, M]]

  /** Waits for the next server event, or returns None if the peer group is closed.
    *
    * @return the next ServerEvent.
    */
  def nextServerEvent(): Task[Option[ServerEvent[A, M]]]
}

object PeerGroup {

  abstract class TerminalPeerGroup[A, M](implicit codec: Codec[M]) extends PeerGroup[A, M]

  sealed trait ServerEvent[A, M]

  object ServerEvent {

    /**
      * Channels that have been created to communicate to the
      * peer identified with the address returned by `processAddress`. Note that if a peer A opens
      * a multicast channel, every peer referenced by the multicast address will receive a channel
      * in the stream returned by this method. This returned channel, could refer either to reply
      * only to A or to the whole group that is referenced by the multicast address. Different
      * implementations could handle this logic differently.
      */
    case class ChannelCreated[A, M](channel: Channel[A, M], release: Release) extends ServerEvent[A, M]

    object ChannelCreated {
      def collector[A, M]: PartialFunction[ServerEvent[A, M], (Channel[A, M], Release)] = {
        case ChannelCreated(c, r) => c -> r
      }
    }

    case class HandshakeFailed[A, M](failure: HandshakeException[A]) extends ServerEvent[A, M]

    object HandshakeFailed {
      def collector[A]: PartialFunction[ServerEvent[A, _], HandshakeException[A]] = {
        case HandshakeFailed(failure) => failure
      }
    }

    implicit class ServerOps[A, M](observable: Observable[ServerEvent[A, M]]) {
      def collectChannelCreated: Observable[(Channel[A, M], Release)] = observable.collect(ChannelCreated.collector)
      def collectHandshakeFailure: Observable[HandshakeException[A]] = observable.collect(HandshakeFailed.collector)
    }
  }

  private def initializationErrorMsg(config: Any) =
    s"Failed initialization of peer group member with config $config. Cause follows."

  class ChannelSetupException[A](val to: A, val cause: Throwable)
      extends RuntimeException(s"Error establishing channel to $to.", cause)

  class ChannelAlreadyClosedException[A](val to: A, val from: A)
      extends RuntimeException(s"Channel from $from, to $to has already been closed")

  class ChannelBrokenException[A](val to: A, val cause: Throwable)
      extends RuntimeException(s"Channel broken to $to.", cause)

  class MessageMTUException[A](val to: A, val size: Long)
      extends RuntimeException(s"Unsupported message of length $size.")

  class HandshakeException[A](val to: A, val cause: Throwable)
      extends RuntimeException(s"Handshake failed to $to.", cause)

}
