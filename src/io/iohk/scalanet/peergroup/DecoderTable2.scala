package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import scala.collection.mutable

private[scalanet] class DecoderTable2() {
  private val decoderWrappers: mutable.Map[(Int, Int), ByteBuffer => Unit] =
    new ConcurrentHashMap[(Int, Int), ByteBuffer => Unit]().asScala

  def put(channelId: (Int, Int), decoderWrapper: ByteBuffer => Unit): Option[ByteBuffer => Unit] =
    decoderWrappers.put(channelId, decoderWrapper)

//  def entries(channelId: (Int, Int)): Map[(Int, Int), ByteBuffer => Unit] = {
//    decoderWrappers.mapValues(f => f(address)).toMap
//  }
}
