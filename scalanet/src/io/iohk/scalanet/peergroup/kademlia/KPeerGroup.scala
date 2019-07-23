package io.iohk.scalanet.peergroup.kademlia

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.ConcurrentHashMap

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

import scala.collection.JavaConverters._

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

  private val channelStates =
    new ConcurrentHashMap[UnderlyingChannel[M], ChannelImpl[M]]().asScala

  private def acceptNodeRecord(channel: UnderlyingChannel[M], nodeRecord: NodeRecord): Unit = {
    // verify the signature of the node record (where does the public key come from)?
    // (what does this prove?)

    val nodeId = nodeRecord.id
    if (!channelStates.contains(channel)) {
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
        channelStates.put(channel, newChannel)
        server.onNext(ChannelCreated(newChannel))
        debug(s"Handshake complete for $nodeRecord. Notifying $server with ${server.size} subscribers.")
      }
    } else {
      debug(s"Handshake ignored for duplicate node record: $nodeRecord")
    }
  }

  override def client(to: BitVector): Task[Channel[BitVector, M]] = {
    val underlyingChannelTask = Task
      .fromFuture(kRouter.get(to))
      .flatMap { record =>
        debug(s"Routing table lookup returns peer $record. Creating new channel.")
        underlyingPeerGroup.client(InetMultiAddress(new InetSocketAddress(record.ip, record.tcp)))
      }
      .onErrorRecoverWith {
        case t =>
          debug(s"Routing table lookup failed for peer ${to.toHex}. Raising an error.")
          Task.raiseError(new ChannelSetupException[BitVector](to, t))
      }

    for {
      underlyingChannel <- underlyingChannelTask
      syn <- underlyingChannel.sendMessage(Left(kRouter.config.nodeRecord))
      _ <- Task(debug(s"Syn sent to peer ${to.toHex}"))
      ack <- underlyingChannel.in.headL
    } yield {
      ack match {
        case Left(nodeRecord) =>
          debug(s"Ack received from peer $nodeRecord")
          // should probably check that the node record received here matches the to parameter.
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

  override def shutdown(): Task[Unit] = Task.unit

  private def debug(msg: String): Unit = {
    log.debug(s"${kRouter.config.nodeId.toHex} $msg")
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
