package io.iohk.scalanet.peergroup.addressing

/** The idea here is to show again how the header idea can suit better when
  * connection establishment events do not trigger Channel instances creation
  * This allows to guarantee that whenever we want to build a channel, we have
  * the a message in the `in` stream and hence we can extract the address from
  * that message's header. This allows to remove handshakes.
  */
import io.iohk.scalanet.peergroup.PeerGroup.{HandshakeException, ServerEvent}
import io.iohk.scalanet.peergroup.{Channel, Connection, InetMultiAddress, PeerGroup}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import HeaderAddressingWithConnection._

abstract class HeaderAddressingWithConnection[A, U, M](
    applicationAddress: A,
    underlyingPeerGroup: PeerGroup[U, Message[A, M]]
)(implicit scheduler: Scheduler)
    extends PeerGroup[A, M] {

  def underlying(address: A): U

  override def processAddress: A = applicationAddress

  override def initialize(): Task[Unit] = underlyingPeerGroup.initialize()

  override def client(to: A): Task[Channel[A, M]] =
    underlyingPeerGroup.client(underlying(to)) map { ch =>
      HeaderChannelWithConnection(processAddress, to, ch)
    }

  override def server(): Observable[PeerGroup.ServerEvent[A, M]] = underlyingPeerGroup.server().flatMap {
    case ServerEvent.ChannelCreated(channel) =>
      Observable.fromTask {
        for {
          from <- channel.in.collect { case Message(add, _) => add }.headL
        } yield ServerEvent.ChannelCreated(HeaderChannelWithConnection(processAddress, from, channel))
      }
    case ServerEvent.HandshakeFailed(failure) =>
      Observable.fromTask {
        Task.now {
          // Note that we can't report the external address
          val wrongAddressToReport = processAddress
          ServerEvent.HandshakeFailed(new HandshakeException[A](wrongAddressToReport, failure.cause))
        }
      }
    case ServerEvent.NewConnectionArrived(connection) =>
      Observable.fromTask {
        Task.now {
          ServerEvent.NewConnectionArrived(HeaderConnection(processAddress, connection))
        }
      }
  }

  override def shutdown(): Task[Unit] = underlyingPeerGroup.shutdown()
}

object HeaderAddressingWithConnection {
  case class Message[A, M](address: A, message: M)

  case class HeaderChannelWithConnection[A, U, M](
      sourceAddress: A,
      destination: A,
      underlyingChannel: Channel[U, Message[A, M]]
  )(implicit scheduler: Scheduler)
      extends Channel[A, M] {

    // NOTE: We now don't need to reply to a handshake here

    override def to: A = destination

    override def sendMessage(message: M): Task[Unit] = {
      underlyingChannel.sendMessage(Message(sourceAddress, message))
    }

    override def in: Observable[M] =
      underlyingChannel.in.collect {
        case Message(sebder, message) if sebder == to => message
      }

    override def close(): Task[Unit] = underlyingChannel.close()
  }

  case class HeaderConnection[A, M](source: A, underlyingConnection: Connection[Message[A, M]]) extends Connection[M] {
    override def from: InetMultiAddress = underlyingConnection.from
    override def sendMessage(m: M): Task[Unit] =
      underlyingConnection.sendMessage(Message(source, m))
    override def close(): Task[Unit] = underlyingConnection.close()
  }
}
