package io.iohk.scalanet.peergroup.addressing
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import ControlMessageAddressing.{ControlMessage, ControlMessageChannel}
import io.iohk.scalanet.peergroup.PeerGroup.{HandshakeException, ServerEvent}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

abstract class ControlMessageAddressing[A, U, M](
    applicationAddress: A,
    underlyingPeerGroup: PeerGroup[U, ControlMessage[A, M]]
)(implicit scheduler: Scheduler)
    extends PeerGroup[A, M] {

  def underlying(address: A): U

  override def processAddress: A = applicationAddress

  override def initialize(): Task[Unit] = underlyingPeerGroup.initialize()

  override def client(to: A): Task[Channel[A, M]] =
    underlyingPeerGroup.client(underlying(to)) map { ch =>
      ControlMessageChannel(processAddress, to, ch)
    }

  override def server(): Observable[PeerGroup.ServerEvent[A, M]] = {
    underlyingPeerGroup.server().flatMap {
      case ServerEvent.ChannelCreated(channel) =>
        Observable.fromTask {
          for {
            _ <- channel.sendMessage(ControlMessage.Address(processAddress))
            from <- channel.in.collect { case ControlMessage.Address(remote) => remote }.headL
          } yield ServerEvent.ChannelCreated(ControlMessageChannel(processAddress, from, channel))
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
  }

  override def shutdown(): Task[Unit] = underlyingPeerGroup.shutdown()
}

object ControlMessageAddressing {

  case class ControlMessageChannel[A, U, M](
      sourceAddress: A,
      destination: A,
      underlyingChannel: Channel[U, ControlMessage[A, M]]
  )(implicit scheduler: Scheduler)
      extends Channel[A, M] {

    // We could send the control message right now on channel creation
    // underlyingChannel.sendMessage(ControlMessage.Address(sourceAddress)).runAsync
    // However, in this sketch we just reply to the server request
    underlyingChannel.in.foreach {
      case ControlMessage.Address(a) =>
        assert(a == to) // possible check
        underlyingChannel.sendMessage(ControlMessage.Address(sourceAddress)).runAsync
      case _ =>
    }

    override def to: A = destination

    override def sendMessage(message: M): Task[Unit] = {
      underlyingChannel.sendMessage(ControlMessage.Message(message))
    }

    override def in: Observable[M] =
      underlyingChannel.in.collect {
        case ControlMessage.Message(message) => message
      }

    override def close(): Task[Unit] = underlyingChannel.close()
  }

  sealed trait ControlMessage[+A, +M]
  object ControlMessage {
    case class Address[A](address: A) extends ControlMessage[A, Nothing]
    case class Message[M](message: M) extends ControlMessage[Nothing, M]
  }

}
