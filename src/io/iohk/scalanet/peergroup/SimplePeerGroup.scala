package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.messagestream.MessageStream
import io.iohk.scalanet.peergroup.PeerGroup.{Lift, NonTerminalPeerGroup}
import io.iohk.scalanet.peergroup.SimplePeerGroup.Config

import scala.collection.mutable
import scala.language.higherKinds
import scala.collection.JavaConverters._
import io.iohk.decco.auto._
import io.iohk.decco._
import SimplePeerGroup._
import monix.eval.Task

class SimplePeerGroup[A: PartialCodec, F[_], AA: PartialCodec](
    val config: Config[A, AA],
    underLyingPeerGroup: PeerGroup[AA, F]
)(
    implicit liftF: Lift[F]
) extends NonTerminalPeerGroup[A, F, AA](underLyingPeerGroup) {

  private val routingTable: mutable.Map[A, AA] = new ConcurrentHashMap[A, AA]().asScala

  private val msgPartialCodec: PartialCodec[PeerMessage[A, AA]] = PartialCodec[PeerMessage[A, AA]]
  private val msgCodec = Codec.heapCodec(msgPartialCodec)

  override val processAddress: A = config.processAddress
  override val messageStream: MessageStream[ByteBuffer] = underLyingPeerGroup.messageStream()

  private val controlChannel = underLyingPeerGroup.createMessageChannel[PeerMessage[A, AA]]()

  controlChannel.inboundMessages.foreach {
    case EnrolMe(address, underlyingAddress) =>
      routingTable += address -> underlyingAddress
      controlChannel
        .sendMessage(underlyingAddress, Enroled(address, underlyingAddress, routingTable.toList))
      println(s"$processAddress: GOT AN ENROLL ME MESSAGE $address, $underlyingAddress")
    case _ => ()
  }

  // TODO if no known peers, create a default routing table with just me.
  // TODO otherwise, enroll with one or more known peers (and obtain/install their routing table here).

  override def sendMessage(address: A, message: ByteBuffer): F[Unit] = {
    // TODO if necessary frame the buffer with peer group specific fields
    // Lookup A in the routing table to obtain an AA for the underlying group.
    // Call sendMessage on the underlyingPeerGroup
    val underLineAddress = routingTable(address)
    underLyingPeerGroup.sendMessage(underLineAddress, message)

  }

  override def shutdown(): F[Unit] = underLyingPeerGroup.shutdown()

  override def initialize(): F[Unit] = {
    routingTable += processAddress -> underLyingPeerGroup.processAddress

    if (config.knownPeers.nonEmpty) {
      val (knownPeerAddress, knownPeerAddressUnderlying) = config.knownPeers.head
      routingTable += knownPeerAddress -> knownPeerAddressUnderlying

      underLyingPeerGroup.sendMessage(
        knownPeerAddressUnderlying,
        msgCodec.encode(EnrolMe(config.processAddress, underLyingPeerGroup.processAddress))
      )

      val enrolledTask: Task[Unit] = Task.deferFutureAction { implicit scheduler =>
        controlChannel.inboundMessages.dropWhile(msg => !msg.isInstanceOf[Enroled[A, AA]]).head().map {
          case Enroled(_, _, newRoutingTable) =>
            routingTable.clear()
            routingTable ++= newRoutingTable
            println(s"$processAddress: enrolled and installed new routing table $newRoutingTable")
          case _ => ()
        }
      }

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
