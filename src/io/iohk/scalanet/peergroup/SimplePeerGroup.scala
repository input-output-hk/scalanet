package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.messagestream.MessageStream
import io.iohk.scalanet.peergroup.PeerGroup.{Lift, NonTerminalPeerGroup}
import io.iohk.scalanet.peergroup.SimplePeerGroup.Config

import scala.collection.mutable
import scala.language.higherKinds
import scala.collection.JavaConverters._

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
  override val messageStream: MessageStream[ByteBuffer] = underLinePeerGroup.messageStream()

  override val processAddress: A = config.processAddress

  def init(): SimplePeerGroup[A, F, AA] = {
    routingTable += processAddress -> underLinePeerGroup.processAddress
    routingTable ++= config.knownPeers
    this
  }
}

object SimplePeerGroup {
  case class Config[A, AA](processAddress: A, knownPeers: Map[A, AA])
}
