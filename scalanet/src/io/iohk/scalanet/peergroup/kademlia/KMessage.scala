package io.iohk.scalanet.peergroup.kademlia

import java.util.UUID

import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import scodec.bits.BitVector

sealed trait KMessage {
  def requestId: UUID
  def nodeRecord: NodeRecord
}

object KMessage {

  sealed trait KRequest extends KMessage

  object KRequest {
    case class FindNodes(requestId: UUID, nodeRecord: NodeRecord, targetNodeId: BitVector) extends KRequest
  }

  sealed trait KResponse extends KMessage

  object KResponse {
    case class Nodes(requestId: UUID, nodeRecord: NodeRecord, nodes: Seq[NodeRecord]) extends KResponse
  }
}
