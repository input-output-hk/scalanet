package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco.Codec.heapCodec
import io.iohk.decco.PartialCodec.{DecodeResult, Failure}
import io.iohk.scalanet.messagestream.MessageStream
import io.iohk.scalanet.peergroup.PeerGroup.{Lift, NonTerminalPeerGroup}
import io.iohk.scalanet.peergroup.SimplePeerGroup.Config

import scala.collection.mutable
import scala.language.higherKinds
import scala.collection.JavaConverters._
import io.iohk.decco.auto._
import io.iohk.decco._

import SimplePeerGroup._

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

  private val handle: (Int, ByteBuffer) => Unit = (nextIndex, byteBuffer) => {
    val messageE: Either[Failure, DecodeResult[PeerMessage[A, AA]]] = msgPartialCodec.decode(nextIndex, byteBuffer)

    messageE.map(result => {
      val peerMessage = result.decoded
      peerMessage match {
        case EnrolMe(address, underlyingAddress) =>
          routingTable += address -> underlyingAddress
          underLyingPeerGroup
            .sendMessage(underlyingAddress, msgCodec.encode(Enroled(address, underlyingAddress, routingTable.toList)))
          println(s"GOT AN ENROLL ME MESSAGE $address, $underlyingAddress")
        case Enroled(address, underlyingAddress, newRoutingTable) =>
          routingTable.clear()
          routingTable ++= newRoutingTable
          println(s"$address, enrolled and installed new routing table $newRoutingTable")
      }
    })
  }

  private val decoderWrappers: Map[String, (Int, ByteBuffer) => Unit] =
    Map(msgPartialCodec.typeCode -> handle)

  messageStream.foreach { b =>
    println(s"GOT A MESSAGE. DECODING IT." + b.toString)
    Codec.decodeFrame(decoderWrappers, 0, b)
  }

  init()

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

  // TODO create subscription to underlying group's messages
  // TODO process messages from underlying (remove any fields added by this group to get the user data)
  // TODO add the user message to this stream
  // Codec[String], Codec[Int], Codec[PeerGroupMessage]

  def init(): SimplePeerGroup[A, F, AA] = {
    routingTable += processAddress -> underLyingPeerGroup.processAddress
    config.knownPeers.headOption.foreach({
      case (knownPeerAddress, knownPeerAddressUnderlying) =>
        routingTable += knownPeerAddress -> knownPeerAddressUnderlying
        underLyingPeerGroup.sendMessage(
          knownPeerAddressUnderlying,
          msgCodec.encode(EnrolMe(config.processAddress, underLyingPeerGroup.processAddress))
        )
    })
    this
  }
}

object SimplePeerGroup {

  sealed trait PeerMessage[A, AA]

  case class EnrolMe[A, AA](myAddress: A, myUnderlyingAddress: AA) extends PeerMessage[A, AA]

  case class Enroled[A, AA](address: A, underlyingAddress: AA, routingTable: List[(A, AA)]) extends PeerMessage[A, AA]

  case class Config[A, AA](processAddress: A, knownPeers: Map[A, AA])

  object EnrolMe {
    //    implicit def toByteBuffer[A,AA](implicit enrolMe: EnrolMe[A,AA]): ByteBuffer =
    //      heapCodec[EnrolMe[A,AA]].encode(enrolMe)
    //
    //    implicit def toEnrolMe[A,AA](implicit byteBuffer: ByteBuffer): Either[DecodeFailure, EnrolMe[A, AA]] =
    //      heapCodec[EnrolMe[A,AA]].decode(byteBuffer)

    implicit def toByteBuffer(implicit enrolMe: EnrolMe[String, InetSocketAddress]): ByteBuffer =
      heapCodec[EnrolMe[String, InetSocketAddress]].encode(enrolMe)

    implicit def toEnrolMe(implicit byteBuffer: ByteBuffer): Either[DecodeFailure, EnrolMe[String, InetSocketAddress]] =
      heapCodec[EnrolMe[String, InetSocketAddress]].decode(byteBuffer)

  }

}
