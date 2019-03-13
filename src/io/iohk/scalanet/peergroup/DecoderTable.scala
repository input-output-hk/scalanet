package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.collection.JavaConverters._

private[scalanet] class DecoderTable[A]() {
  private val decoderWrappers: mutable.Map[String, A => (Int, ByteBuffer) => Unit] =
    new ConcurrentHashMap[String, A => (Int, ByteBuffer) => Unit]().asScala

  def put(typeCode: String, decoderWrapper: A => (Int, ByteBuffer) => Unit): Option[A => (Int, ByteBuffer) => Unit] =
    decoderWrappers.put(typeCode, decoderWrapper)

  def entries(address: A): Map[String, (Int, ByteBuffer) => Unit] = {
    decoderWrappers.mapValues(f => f(address)).toMap
  }
}
