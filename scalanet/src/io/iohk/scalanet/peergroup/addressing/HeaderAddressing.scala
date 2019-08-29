package io.iohk.scalanet.peergroup.addressing
import io.iohk.scalanet.peergroup.PeerGroup.{HandshakeException, ServerEvent}
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import io.iohk.scalanet.peergroup.addressing.HeaderAddressing.{Header, HeaderChannel}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

abstract class HeaderAddressing[A, U, M](
    applicationAddress: A,
    underlyingPeerGroup: PeerGroup[U, Header[A, M]]
)(implicit scheduler: Scheduler)
    extends PeerGroup[A, M] {

  def underlying(address: A): U

  override def processAddress: A = applicationAddress

  override def initialize(): Task[Unit] = underlyingPeerGroup.initialize()

  override def client(to: A): Task[Channel[A, M]] =
    underlyingPeerGroup.client(underlying(to)) map { ch =>
      HeaderChannel(processAddress, to, ch)
    }

  override def server(): Observable[PeerGroup.ServerEvent[A, M]] = underlyingPeerGroup.server().flatMap {
    case ServerEvent.ChannelCreated(channel) =>
      Observable.fromTask {
        for {
          _ <- channel.sendMessage(Header.Address(processAddress))
          from <- channel.in.collect { case Header.Address(remote) => remote }.headL
        } yield ServerEvent.ChannelCreated(HeaderChannel(processAddress, from, channel))
      }
    case ServerEvent.HandshakeFailed(failure) =>
      Observable.fromTask {
        Task.now {
          // Note that we can't report the external address
          val wrongAddressToReport = processAddress
          ServerEvent.HandshakeFailed(new HandshakeException[A](wrongAddressToReport, failure.cause))
        }
      }
    case ServerEvent.NewConnectionArrived(connection) => ???
  }

  override def shutdown(): Task[Unit] = underlyingPeerGroup.shutdown()
}

object HeaderAddressing {
  sealed trait Header[+A, +M]
  object Header {
    case class Message[A, M](address: A, message: M) extends Header[A, M]
    // note that this is needed because we receive ChannelCreated events without messages
    // (triggered by connections) forcing us to add this control message. If we add a
    // connection received event that provides a different class than Channel, we could
    // avoid this extra control message. See HeaderAddressingWithConnection to see this
    // implemented
    case class Address[A](addres: A) extends Header[A, Nothing]
  }

  case class HeaderChannel[A, U, M](
      sourceAddress: A,
      destination: A,
      underlyingChannel: Channel[U, Header[A, M]]
  )(implicit scheduler: Scheduler)
      extends Channel[A, M] {

    underlyingChannel.in
    underlyingChannel.in.foreach {
      case Header.Address(a) =>
        assert(a == to) // possible check
        underlyingChannel.sendMessage(Header.Address(sourceAddress)).runAsync
      case _ =>
    }

    override def to: A = destination

    override def sendMessage(message: M): Task[Unit] = {
      underlyingChannel.sendMessage(Header.Message(sourceAddress, message))
    }

    override def in: Observable[M] =
      underlyingChannel.in.collect {
        case Header.Message(sebder, message) if sebder == to => message
      }

    override def close(): Task[Unit] = underlyingChannel.close()
  }
}
