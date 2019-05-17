package io.iohk.scalanet.peergroup

import io.iohk.decco.Codec
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.concurrent.Await
import scala.concurrent.duration.Duration

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
    * @param message the message to send
    * @return Where the underlying technology supports ACKs, implementations
    *         should wait for the ack and return a successful or unsuccessful Task accordingly.
    *         Where the underlying technology does not support ACKs (such as datagram protocols)
    *         implementations may return immediately with a successful Task.
    */
  def sendMessage(message: M): Task[Unit]

  /**
    * The inbound stream of messages coming from the remote peer.
    */
  def in: Observable[M]

  /**
    * Terminate the Channel and clean up any resources.
    * @return Implementations should wait for termination of the underlying network resources
    *         and return a successful Task.
    */
  def close(): Task[Unit]
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
  def processAddress: A
  def initialize(): Task[Unit]
  def client(to: A): Task[Channel[A, M]]
  def server(): Observable[Channel[A, M]]
  def shutdown(): Task[Unit]
}

object PeerGroup {

  abstract class TerminalPeerGroup[A, M](implicit codec: Codec[M]) extends PeerGroup[A, M]

  /**
    * This function provides a consistent approach to creating and initializing peer group instances.
    * @param pg lazy peer group instance.
    * @param config the peer group config. Only used for error reporting.
    * @param scheduler a monix scheduler to the run the peer group's initialization.
    * @tparam PG the PeerGroup type.
    * @return this method returns the peer group in an Either. This makes is more suitable
    *         for use in normal code flow, rather than application context setup.
    */
  def create[PG <: PeerGroup[_, _]](pg: => PG, config: Any)(
      implicit scheduler: Scheduler
  ): Either[InitializationError, PG] =
    try {
      Await.result(pg.initialize().runToFuture, Duration.Inf)
      Right(pg)
    } catch {
      case t: Throwable =>
        Left(InitializationError(initializationErrorMsg(config), t))
    }

  /**
    * This function provides a consistent approach to creating and initializing peer group instances.
    * @param pg lazy peer group instance.
    * @param config the peer group config. Only used for error reporting.
    * @param scheduler a monix scheduler to the run the peer group's initialization.
    * @tparam PG the PeerGroup type.
    * @return this method returns the peer group or throws an exception. This makes is more suitable
    *         for creating peer groups at application startup, where you would like to abort the startup
    *         process when errors arise.
    */
  def createOrThrow[PG <: PeerGroup[_, _]](pg: => PG, config: Any)(implicit scheduler: Scheduler): PG =
    try {
      Await.result(pg.initialize().runToFuture, Duration.Inf)
      pg
    } catch {
      case t: Throwable =>
        throw new IllegalStateException(initializationErrorMsg(config), t)
    }

  private def initializationErrorMsg(config: Any) =
    s"Failed initialization of peer group member with config $config. Cause follows."
}
