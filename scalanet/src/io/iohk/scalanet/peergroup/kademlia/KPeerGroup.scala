package io.iohk.scalanet.peergroup.kademlia

import java.net.{InetAddress, InetSocketAddress}

import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.PeerGroup.{ChannelSetupException, HandshakeException, ServerEvent}
import io.iohk.scalanet.peergroup.kademlia.KPeerGroup.{ChannelImpl, NodeRecord, UnderlyingChannel}
import io.iohk.scalanet.peergroup.{Channel, InetMultiAddress, PeerGroup}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.subjects.Subject
import org.slf4j.{Logger, LoggerFactory}
import scodec.bits.BitVector

// PeerGroup using node records for addressing.
// These node records are derived from Ethereum node records (https://eips.ethereum.org/EIPS/eip-778)
class KPeerGroup[M](
    val kRouter: KRouter[NodeRecord],
    val server: Subject[ServerEvent[BitVector, M], ServerEvent[BitVector, M]],
    underlyingPeerGroup: PeerGroup[InetMultiAddress, Either[NodeRecord, M]]
)(implicit scheduler: Scheduler)
    extends PeerGroup[BitVector, M] {

  private val log = LoggerFactory.getLogger(getClass)

  override def processAddress: BitVector = kRouter.config.nodeId

  override def initialize(): Task[Unit] = {
    // The protocol defined here requires that a peer
    // connects then sends it node record as the first message
    // (it could send earlier messages but they will be ignored)
    // This node will then create a channel and reply with its
    // own node record.
    underlyingPeerGroup
      .server()
      .collect(ChannelCreated.collector)
      .mergeMap(
        channel =>
          channel.in.collect {
            case Left(nodeRecord) =>
              acceptNodeRecord(channel, nodeRecord)
          }
      )
      .subscribe()

    Task.unit
  }

  override def client(to: BitVector): Task[Channel[BitVector, M]] = {
    val underlyingChannelTask = Task
      .fromFuture(kRouter.get(to)) // make the underlying kademlia lookup
      .flatMap { record => // use the lookup's address info to obtain an underlying channel...
        debug(s"Routing table lookup returns peer $record. Creating new channel.")
        underlyingPeerGroup.client(InetMultiAddress(new InetSocketAddress(record.ip, record.tcp)))
      }
      .onErrorRecoverWith {
        case t =>
          debug(s"Routing table lookup failed for peer ${to.toHex}. Raising an error.")
          Task.raiseError(new ChannelSetupException[BitVector](to, t))
      }

    for {
      // after creating the underlying channel attempt to synchronize node records with the peer
      underlyingChannel <- underlyingChannelTask
      syn <- underlyingChannel.sendMessage(Left(kRouter.config.nodeRecord))
      _ <- Task(debug(s"Syn sent to peer ${to.toHex}"))
      ack <- underlyingChannel.in.headL
    } yield {
      ack match {
        case Left(nodeRecord) =>
          // Once the peer's node record is received, create a new channel and return it to the caller.
          debug(s"Ack received from peer $nodeRecord")
          // should probably check that the node record received here matches the to parameter.
          // TODO what other checks make sense?
          new ChannelImpl[M](
            to,
            kRouter.config.nodeId,
            log,
            underlyingChannel
          )
        case Right(_) =>
          // messages received without an ack. This is a protocol violation.
          throw new HandshakeException(
            to,
            new Exception("messages received without an ack. This is a protocol violation.")
          )

      }
    }
  }

  // TODO any open channels will remain operational.
  // Arguably, we should keep references to them and
  // explicitly close them during shutdown.
  override def shutdown(): Task[Unit] = Task.unit

  private def debug(msg: String): Unit = {
    log.debug(s"${kRouter.config.nodeId.toHex} $msg")
  }

  private def acceptNodeRecord(channel: UnderlyingChannel[M], nodeRecord: NodeRecord): Unit = {
    // verify the signature of the node record?
    // (what does this prove?)

    val nodeId = nodeRecord.id
    debug(s"Setting up new channel to $nodeRecord.")

    // send the peer our own node record
    channel.sendMessage(Left(kRouter.config.nodeRecord)).runAsync.foreach { _ =>
      debug(s"Acknowledgement sent to $nodeRecord.")

      val newChannel = new ChannelImpl[M](
        nodeId,
        kRouter.config.nodeId,
        log,
        channel
      )
      server.onNext(ChannelCreated(newChannel))
      debug(s"Handshake complete for $nodeRecord. Notifying $server with ${server.size} subscribers.")
    }
  }
}

object KPeerGroup {

  type UnderlyingChannel[M] = Channel[InetMultiAddress, Either[NodeRecord, M]]

  // TODO node records require an additional
  // signature (why)
  // sequence number (why)
  // compressed public key (why)
  // TODO understand what these things do, which we need an implement.
  case class NodeRecord(id: BitVector, ip: InetAddress, tcp: Int, udp: Int) {
    override def toString: String =
      s"NodeRecord(id = ${id.toHex}, ip = $ip, tcp = $tcp, udp = $udp)"
  }

  private class ChannelImpl[M](
      val to: BitVector,
      val from: BitVector,
      val log: Logger,
      underlyingChannel: UnderlyingChannel[M]
  ) extends Channel[BitVector, M] {

    override def toString: String =
      s"${from.toHex} Channel(${from.toHex}, ${to.toHex})"

    override def sendMessage(message: M): Task[Unit] = {
      log.debug(s"$this: sending outbound message $message")
      underlyingChannel.sendMessage(Right(message))
    }

    override def close(): Task[Unit] = {
      underlyingChannel.close()
    }

    override def in: Observable[M] = underlyingChannel.in.collect { case Right(message) => message }
  }

}
