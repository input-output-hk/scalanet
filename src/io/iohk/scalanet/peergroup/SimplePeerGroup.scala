package io.iohk.scalanet.peergroup

import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.peergroup.PeerGroup.NonTerminalPeerGroup
import io.iohk.scalanet.peergroup.SimplePeerGroup.Config

import scala.collection.mutable
import scala.collection.JavaConverters._
import io.iohk.decco.auto._
import io.iohk.decco._
import SimplePeerGroup._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

class SimplePeerGroup[A, AA](
    val config: Config[A, AA],
    underLyingPeerGroup: PeerGroup[AA]
)(
    implicit aCodec: Codec[A],
    aaCodec: Codec[AA],
    scheduler: Scheduler
) extends NonTerminalPeerGroup[A, AA](underLyingPeerGroup) {

  private val log = LoggerFactory.getLogger(getClass)

  private val routingTable: mutable.Map[A, AA] = new ConcurrentHashMap[A, AA]().asScala

  private val controlChannel = {
    implicit val apc: PartialCodec[A] = aCodec.partialCodec
    implicit val aapc: PartialCodec[AA] = aaCodec.partialCodec
    underLyingPeerGroup.createMessageChannel[PeerMessage[A, AA]]()
  }

  override val processAddress: A = config.processAddress

  controlChannel.inboundMessages
    .collect {
      case e @ EnrolMe(address, underlyingAddress) =>
        routingTable += address -> underlyingAddress
        controlChannel
          .sendMessage(underlyingAddress, Enrolled(address, underlyingAddress, routingTable.toList))
          .runToFuture
        log.debug(
          s"Processed enrolment message $e at address '$processAddress' with corresponding routing table update."
        )
    }
    .foreach { _ =>
      ()
    }

  override def sendMessage[T: Codec](address: A, message: T): Task[Unit] = {
    // Lookup A in the routing table to obtain an AA for the underlying group.
    // Call sendMessage on the underlyingPeerGroup
    val underLyingAddress = routingTable(address)
    underLyingPeerGroup.sendMessage(underLyingAddress, message)
  }

  override def createMessageChannel[MessageType]()(
      implicit codec: Codec[MessageType]
  ): MessageChannel[A, MessageType] = {
    val underlyingChannel: MessageChannel[AA, MessageType] = underLyingPeerGroup.createMessageChannel[MessageType]()
    //    val messageChannel = new MessageChannel[A, MessageType](this)
    //    decoderTable.decoderWrappers.put(codec.typeCode.id, messageChannel.handleMessage)
    //underlyingChannel.inboundMessages.map( b => (processAddress,b))
    new MessageChannel[A, MessageType](this, underlyingChannel.inboundMessages)
  }

  override def messageChannel[MessageType: Codec]: Observable[MessageType] =
    underLyingPeerGroup.messageChannel[MessageType]

  override def shutdown(): Task[Unit] = underLyingPeerGroup.shutdown()

  override def initialize(): Task[Unit] = {
    routingTable += processAddress -> underLyingPeerGroup.processAddress

    if (config.knownPeers.nonEmpty) {
      val (knownPeerAddress, knownPeerAddressUnderlying) = config.knownPeers.head
      routingTable += knownPeerAddress -> knownPeerAddressUnderlying

      val enrolledTask: Task[Unit] = controlChannel.inboundMessages.collect {
        case Enrolled(_, _, newRoutingTable) =>
          routingTable.clear()
          routingTable ++= newRoutingTable
          log.debug(s"Peer address '$processAddress' enrolled into group and installed new routing table:")
          log.debug(s"$newRoutingTable")
      }.headL

      controlChannel
        .sendMessage(
          knownPeerAddressUnderlying,
          EnrolMe(config.processAddress, underLyingPeerGroup.processAddress)
        )
        .runToFuture

      enrolledTask
    } else {
      Task.unit
    }
  }
}

object SimplePeerGroup {

  sealed trait PeerMessage[A, AA]

  case class EnrolMe[A, AA](myAddress: A, myUnderlyingAddress: AA) extends PeerMessage[A, AA]

  case class Enrolled[A, AA](address: A, underlyingAddress: AA, routingTable: List[(A, AA)]) extends PeerMessage[A, AA]

  case class Config[A, AA](processAddress: A, knownPeers: Map[A, AA])
}
