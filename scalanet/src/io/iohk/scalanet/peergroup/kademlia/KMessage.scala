package io.iohk.scalanet.peergroup.kademlia

import java.util.UUID

import scodec.bits.BitVector

sealed trait KMessage[V] {
  def requestId: UUID
  def nodeId: BitVector
  def nodeRecord: V
}

object KademliaMessage {

  sealed trait KRequest[V] extends KMessage[V]

  object KRequest {
    case class FindNodes[V](requestId: UUID, nodeId: BitVector, nodeRecord: V, targetNodeId: BitVector)
        extends KRequest[V]
  }

  sealed trait KResponse[V] extends KMessage[V]

  object KResponse {
    case class Nodes[V](requestId: UUID, nodeId: BitVector, nodeRecord: V, nodes: Seq[(BitVector, V)])
        extends KResponse[V]
  }

}
