package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.peergroup.PeerGroup.{Lift, NonTerminalPeerGroup}
import io.iohk.scalanet.peergroup.SimplePeerGroup.Config

import scala.collection.mutable
import scala.language.higherKinds
import scala.collection.JavaConverters._
import io.iohk.decco.auto._
import io.iohk.decco._
import SimplePeerGroup._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

class SimplePeerGroup[A, F[_], AA](
    val config: Config[A, AA],
    underLyingPeerGroup: PeerGroup[AA, F]
)(
    implicit liftF: Lift[F],
    aCodec: Codec[A],
    aaCodec: Codec[AA],
    scheduler: Scheduler,
) extends NonTerminalPeerGroup[A, F, AA](underLyingPeerGroup) {

  private val log = LoggerFactory.getLogger(getClass)

  private val routingTable: mutable.Map[A, AA] = new ConcurrentHashMap[A, AA]().asScala

  private val controlChannel = {
    implicit val apc: PartialCodec[A] = aCodec.partialCodec
    implicit val aapc: PartialCodec[AA] = aaCodec.partialCodec
    underLyingPeerGroup.createMessageChannel[PeerMessage[A, AA]]()
  }

  override val processAddress: A = config.processAddress
  override val messageStream: Observable[ByteBuffer] = underLyingPeerGroup.messageStream()

  messageStream.foreach { byteBuffer =>
    Codec.decodeFrame(decoderTable.entries, 0, byteBuffer)
  }

  controlChannel.inboundMessages
    .collect {
      case e @ EnrolMe(address, underlyingAddress) =>
        routingTable += address -> underlyingAddress
        controlChannel
          .sendMessage(underlyingAddress, Enroled(address, underlyingAddress, routingTable.toList))
        log.debug(
          s"Processed enrolment message $e at address '$processAddress' with corresponding routing table update."
        )
    }
    .foreach { _ =>
      ()
    }

  // TODO if no known peers, create a default routing table with just me.
  // TODO otherwise, enroll with one or more known peers (and obtain/install their routing table here).

  override def sendMessage(address: A, message: ByteBuffer): F[Unit] = {
    // TODO if necessary frame the buffer with peer group specific fields
    // Lookup A in the routing table to obtain an AA for the underlying group.
    // Call sendMessage on the underlyingPeerGroup
    val underLyingAddress = routingTable(address)
    underLyingPeerGroup.sendMessage(underLyingAddress, message)
  }

  override def shutdown(): F[Unit] = underLyingPeerGroup.shutdown()

  override def initialize(): F[Unit] = {
    routingTable += processAddress -> underLyingPeerGroup.processAddress

    if (config.knownPeers.nonEmpty) {
      val (knownPeerAddress, knownPeerAddressUnderlying) = config.knownPeers.head
      routingTable += knownPeerAddress -> knownPeerAddressUnderlying

      controlChannel.sendMessage(
        knownPeerAddressUnderlying,
        EnrolMe(config.processAddress, underLyingPeerGroup.processAddress)
      )

      val enrolledTask: Task[Unit] = controlChannel.inboundMessages
        .collect {
          case Enroled(_, _, newRoutingTable) =>
            routingTable.clear()
            routingTable ++= newRoutingTable
            log.debug(s"Peer address '$processAddress' enrolled into group and installed new routing table:")
            log.debug(s"$newRoutingTable")
        }
        .headL

      liftF(enrolledTask)
    } else {
      liftF(Task.unit)
    }
  }
}

object SimplePeerGroup {

  sealed trait PeerMessage[A, AA]

  case class EnrolMe[A, AA](myAddress: A, myUnderlyingAddress: AA) extends PeerMessage[A, AA]

  case class Enroled[A, AA](address: A, underlyingAddress: AA, routingTable: List[(A, AA)]) extends PeerMessage[A, AA]

  case class Config[A, AA](processAddress: A, knownPeers: Map[A, AA])

}
