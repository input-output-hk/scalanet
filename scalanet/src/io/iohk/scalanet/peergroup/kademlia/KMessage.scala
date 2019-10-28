package io.iohk.scalanet.peergroup.kademlia

import java.util.UUID

import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import scodec.bits.BitVector

sealed trait KMessage[A] {
  def requestId: UUID
  def nodeRecord: NodeRecord[A]
}

object KMessage {

  sealed trait KRequest[A] extends KMessage[A]

  object KRequest {
    case class FindNodes[A](requestId: UUID, nodeRecord: NodeRecord[A], targetNodeId: BitVector) extends KRequest[A]

    case class Ping[A](requestId: UUID, nodeRecord: NodeRecord[A]) extends KRequest[A]
  }

  sealed trait KResponse[A] extends KMessage[A]

  object KResponse {
    case class Nodes[A](requestId: UUID, nodeRecord: NodeRecord[A], nodes: Seq[NodeRecord[A]]) extends KResponse[A]

    case class Pong[A](requestId: UUID, nodeRecord: NodeRecord[A]) extends KResponse[A]
  }
}
