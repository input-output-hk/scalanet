package io.iohk.network.transport

import io.iohk.network.NodeId

object FrameHeader {
  val defaultTtl = 5
  val ttlLength: Int = 4
  val frameHeaderLength: Int = 4 + NodeId.nodeIdBytes + NodeId.nodeIdBytes + ttlLength

  def apply(src: NodeId, dst: NodeId): FrameHeader = FrameHeader(src, dst, defaultTtl)
}

case class FrameHeader(src: NodeId, dst: NodeId, ttl: Int)

case class Frame[Message](header: FrameHeader, content: Message)
