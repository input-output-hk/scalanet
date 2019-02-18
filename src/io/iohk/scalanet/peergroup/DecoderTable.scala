package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.collection.JavaConverters._

private[scalanet] class DecoderTable() {
  val decoderWrappers: mutable.Map[String, (Int, ByteBuffer) => Unit] =
    new ConcurrentHashMap[String, (Int, ByteBuffer) => Unit]().asScala

  def put(typeCode: String, decoderWrapper: (Int, ByteBuffer) => Unit) =
    decoderWrappers.put(typeCode, decoderWrapper)

  def entries: Map[String, (Int, ByteBuffer) => Unit] = decoderWrappers.toMap
}
