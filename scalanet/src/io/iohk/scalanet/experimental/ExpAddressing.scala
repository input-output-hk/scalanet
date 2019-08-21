package io.iohk.scalanet.experimental

import java.net.InetSocketAddress

import monix.eval.Task
import monix.execution.Scheduler
import ExpAddressing._

abstract class ExpAddressing[A, U, M](
    underlyingPeerGroup: EPeerGroup[U, AddressHeader[A, M]],
    config: HeaderAddressingConfig[A]
)(implicit scheduler: Scheduler)
    extends EPeerGroup[A, M] {

  def underlyingAddress(applicationAddress: A): U

  override def processAddress: A = config.applicationAddress

  override def connect(): Task[Unit] = underlyingPeerGroup.connect()

  override def client(to: A): Task[EChannel[A, M]] = {
    underlyingPeerGroup.client(underlyingAddress(to)).map { uCh =>
      HeaderAddressingChannel[A, U, M](processAddress, to, uCh)
    }
  }

  override def onConnectionArrival(connectionHandler: EConnection[M] => Unit): Unit = {
    underlyingPeerGroup.onConnectionArrival { undCon: EConnection[AddressHeader[A, M]] =>
      connectionHandler(AddressingConnection(processAddress, undCon))
    }
  }

  override def onMessageReception(handler: Envelope[A, M] => Unit): Unit = {
    underlyingPeerGroup.onMessageReception { envelope: Envelope[U, AddressHeader[A, M]] =>
      val addressedMessage = envelope.msg
      val newCh: EChannel[A, M] = AddressingChannel[A, U, M](processAddress, envelope.msg.address, envelope.channel)
      handler(Envelope[A, M](newCh, addressedMessage.address, addressedMessage.msg))
    }
  }

  override def shutdown(): Task[Unit] = underlyingPeerGroup.shutdown()
}

object ExpAddressing {
  case class HeaderAddressingConfig[A](applicationAddress: A)

  case class AddressHeader[A, M](address: A, msg: M)

  case class HeaderAddressingConnection[A, M](add: A, underlyingConn: EConnection[AddressHeader[A, M]])
      extends EConnection[M] {
    override def underlyingAddress: InetSocketAddress = underlyingConn.underlyingAddress

    override def replyWith(m: M): Task[Unit] = underlyingConn.replyWith(AddressHeader(add, m))

    override def close(): Task[Unit] = underlyingConn.close()
  }

  case class HeaderAddressingChannel[A, U, M](
      localAddress: A,
      remoteAddress: A,
      underlyingChannel: EChannel[U, AddressHeader[A, M]]
  ) extends EChannel[A, M] {

    override def to: A = remoteAddress

    override def sendMessage(message: M): Task[Unit] =
      underlyingChannel.sendMessage(AddressHeader(localAddress, message))

    override def close(): Task[Unit] = underlyingChannel.close()
  }
}

case class AddressingConnection[A, M](localAddress: A, undAC: EConnection[AddressHeader[A, M]]) extends EConnection[M] {
  override def underlyingAddress: InetSocketAddress = undAC.underlyingAddress

  override def replyWith(m: M): Task[Unit] = undAC.replyWith(AddressHeader(localAddress, m))

  override def close(): Task[Unit] = undAC.close()
}

case class AddressingChannel[A, U, M](
    localAddress: A,
    remoteAddress: A,
    underlyingChannel: EChannel[U, AddressHeader[A, M]]
) extends EChannel[A, M] {
  override def to: A = remoteAddress

  override def sendMessage(m: M): Task[Unit] = underlyingChannel.sendMessage(AddressHeader(localAddress, m))

  override def close(): Task[Unit] = underlyingChannel.close()
}
