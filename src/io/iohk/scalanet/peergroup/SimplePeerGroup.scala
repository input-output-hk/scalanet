package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco.Codec.heapCodec
import io.iohk.scalanet.messagestream.MessageStream
import io.iohk.scalanet.peergroup.PeerGroup.{Lift, NonTerminalPeerGroup}
import io.iohk.scalanet.peergroup.SimplePeerGroup.{Config}

import scala.collection.mutable
import scala.language.higherKinds
import scala.collection.JavaConverters._
import io.iohk.decco.auto._
import io.iohk.decco._
//import SimplePeerGroup.EnrolMe._

class SimplePeerGroup[A, F[_], AA](val config: Config[A, AA], underLinePeerGroup: PeerGroup[AA, F])(
    implicit liftF: Lift[F]
) extends NonTerminalPeerGroup[A, F, AA](underLinePeerGroup) {

  private val routingTable: mutable.Map[A, AA] = new ConcurrentHashMap[A, AA]().asScala

  // TODO if no known peers, create a default routing table with just me.
  // TODO otherwise, enroll with one or more known peers (and obtain/install their routing table here).

  override def sendMessage(address: A, message: ByteBuffer): F[Unit] = {
    // TODO if necessary frame the buffer with peer group specific fields
    // Lookup A in the routing table to obtain an AA for the underlying group.
    // Call sendMessage on the underlyingPeerGroup
    val underLineAddress = routingTable(address)
    underLinePeerGroup.sendMessage(underLineAddress, message)

  }

  override def shutdown(): F[Unit] = underLinePeerGroup.shutdown()

  // TODO create subscription to underlying group's messages
  // TODO process messages from underlying (remove any fields added by this group to get the user data)
  // TODO add the user message to this stream
  override val messageStream: MessageStream[ByteBuffer] = {
    underLinePeerGroup.messageStream().map { b =>
      b
    //    case me: EnrolMe[String,InetSocketAddress](a,aa) => _ //update the routing table
//      toEnrolMe(_) match {
    ////        case Right(r) => routingTable += (r.myAddress -> r.myUnderlyingAddress)
    ////        case Left(x) => heapCodec[DecodeFailure].encode(x)
    ////      }

    }
  }

  override val processAddress: A = config.processAddress

  def init(): SimplePeerGroup[A, F, AA] = {
    routingTable += processAddress -> underLinePeerGroup.processAddress
    routingTable ++= config.knownPeers
//    routingTable.filterKeys(_ != processAddress).foreach {
//      case (a,aa) => underLinePeerGroup.sendMessage(aa,EnrolMe(processAddress,underLinePeerGroup.processAddress))
//    }
    this
  }
}

object SimplePeerGroup {

  trait PeerMessage[A, AA]

  case class EnrolMe[A, AA](myAddress: A, myUnderlyingAddress: AA) extends PeerMessage[A, AA]

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

  case class Enrolled[A, AA](address: A, underlyingAddress: AA) extends PeerMessage[A, AA]

  object Enrolled {
    implicit def toByteBuffer(implicit enrolled: Enrolled[String, InetSocketAddress]) =
      heapCodec[Enrolled[String, InetSocketAddress]].encode(enrolled)
    implicit def toEnrolled(implicit bf: ByteBuffer) = heapCodec[Enrolled[String, InetSocketAddress]].decode(bf)

  }

}

//abstract class MessageSpec[Content <: AnyRef](implicit contentCt: ClassTag[Content]) {
//  val contentClass: Class[_] = contentCt.runtimeClass
//  final val messageName: String = """Spec\$$""".r.replaceAllIn(getClass.getSimpleName, "")
//
//  def maxLength: Int
//
//  def deserializeData(bytes: Array[Byte]): Try[Content]
//
//  def serializeData(data: Content): Array[Byte]
//
//  override def toString: String = s"MessageSpec($messageName)"
//}
//object PeersSpec extends MessageSpec[KnownPeers] {
//  private val AddressLength = 4
//  private val PortLength    = 4
//  private val DataLength    = 4
//
//  override val messageCode: Message.MessageCode = 2: Byte
//
//  override val maxLength: Int = DataLength + 1000 * (AddressLength + PortLength)
//
//  override def deserializeData(bytes: Array[Byte]): Try[KnownPeers] = Try {
//    val lengthBytes = util.Arrays.copyOfRange(bytes, 0, DataLength)
//    val length      = Ints.fromByteArray(lengthBytes)
//
//    assert(bytes.length == DataLength + (length * (AddressLength + PortLength)), "Data does not match length")
//
//    KnownPeers((0 until length).map { i =>
//      val position     = lengthBytes.length + (i * (AddressLength + PortLength))
//      val addressBytes = util.Arrays.copyOfRange(bytes, position, position + AddressLength)
//      val address      = InetAddress.getByAddress(addressBytes)
//      val portBytes    = util.Arrays.copyOfRange(bytes, position + AddressLength, position + AddressLength + PortLength)
//      new InetSocketAddress(address, Ints.fromByteArray(portBytes))
//    })
//  }
//
//  override def serializeData(peers: KnownPeers): Array[Byte] = {
//    val length      = peers.peers.size
//    val lengthBytes = Ints.toByteArray(length)
//
//    val xs = for {
//      inetAddress <- peers.peers
//      address     <- Option(inetAddress.getAddress)
//    } yield (address.getAddress, inetAddress.getPort)
//
//    xs.foldLeft(lengthBytes) {
//      case (bs, (peerAddress, peerPort)) =>
//        Bytes.concat(bs, peerAddress, Ints.toByteArray(peerPort))
//    }
//  }
//}
