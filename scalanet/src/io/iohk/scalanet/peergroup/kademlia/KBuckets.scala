package io.iohk.scalanet.peergroup.kademlia

import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArraySet}

import io.iohk.scalanet.peergroup.kademlia.KBuckets._
import scodec.bits.BitVector

import scala.collection.JavaConverters._
import scala.collection.mutable

class KBuckets(val baseId: BitVector) {

  private val buckets = new ConcurrentHashMap[Int, KBucket]().asScala

  private val ordering = new XorOrdering(baseId)

  /**
    * Find the n nodes closest to nodeId in kBuckets.
    * Return the resulting node records sorted by distance from the nodeId.
    * The indices into the kBuckets are defined by their distance from referenceNodeId.
    */
  def closestNodes(nodeId: BitVector, n: Int): List[BitVector] = {
    // sketch
    // the id range of a bucket is 2^i bits,
    // where i is the common prefix length between the bucket prefix and the base nodeId.
    // each kbucket covers a distance range [2^i, 2^i+1 - 1]
    // this means that buckets with larger i cover larger ranges.
    // so, for eg, bucket 1 covers 4-2-1=1 node whereas bucket 7 covers 256-128-1=127 nodes
    // in general each bucket covers 2^i+1 - 2^i - 1 = 2^i - 1

    // therefore when finding an arbitrary nodeId
    // find the prefix

    // replace with routing tree described in kademlia paper...
    iterator.toList.sorted(ordering).take(n)
  }

  /**
    * Add a node record into the KBuckets (TODO if there is capacity).
    *
    * @return this KBuckets instance.
    */
  def add(nodeId: BitVector): KBuckets = {
    if (nodeId.length != this.baseId.length)
      throw new IllegalArgumentException(
        s"Illegal attempt to add node id with a length different than the this node id."
      )

    val d = Xor.d(nodeId, this.baseId)

    val bucket: KBucket = buckets.getOrElseUpdate(d, newKBucket)

    bucket.add(nodeId)

    this
  }

  /**
    * Query whether a given nodeId is present in the kbuckets.
    *
    * @return true if present
    */
  def contains(nodeId: BitVector): Boolean = {
    buckets.getOrElse(Xor.d(nodeId, this.baseId), Set.empty[BitVector]).contains(nodeId)
  }

  /**
    * Iterate over all elements in the KBuckets.
    *
    * @return an iterator
    */
  def iterator: Iterator[BitVector] = new Iterator[BitVector] {
    val flatIterator: Iterator[BitVector] = buckets.flatMap(_._2).iterator

    override def hasNext: Boolean = flatIterator.hasNext

    override def next(): BitVector = flatIterator.next()
  }

  /**
    * Remove an element by id.
    *
    * @param nodeId the nodeId to remove
    * @return
    */
  def remove(nodeId: BitVector): KBuckets = {
    buckets
      .get(Xor.d(nodeId, this.baseId))
      .foreach(
        bucket =>
          bucket
            .find(_ == nodeId)
            .foreach(record => bucket.remove(record))
      )
    this
  }

  private def newKBucket: KBucket =
    new CopyOnWriteArraySet[BitVector]().asScala
}

object KBuckets {

  type KBucket = mutable.Set[BitVector]
}
