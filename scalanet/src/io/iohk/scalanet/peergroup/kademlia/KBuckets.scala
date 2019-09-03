package io.iohk.scalanet.peergroup.kademlia

import java.util.concurrent.ConcurrentSkipListSet

import scodec.bits.BitVector

import scala.collection.JavaConverters._

/**
  * Skeletal kbucket implementation.
  * @param baseId the nodes own id.
  */
class KBuckets(val baseId: BitVector) {

  private val nodeIds =
    new ConcurrentSkipListSet[BitVector](new XorOrdering(baseId)).asScala

  add(baseId)

  /**
    * Find the n nodes closest to nodeId in kBuckets.
    * Return the resulting node records sorted by distance from the nodeId.
    * The indices into the kBuckets are defined by their distance from referenceNodeId.
    */
  def closestNodes(nodeId: BitVector, n: Int): List[BitVector] = {
    // replace with routing tree described in kademlia paper...
    nodeIds.toList.sorted(new XorOrdering(nodeId)).take(n)
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

    nodeIds.add(nodeId)

    this
  }

  /**
    * Query whether a given nodeId is present in the kbuckets.
    *
    * @return true if present
    */
  def contains(nodeId: BitVector): Boolean = {
    nodeIds.contains(nodeId)
  }

  /**
    * Iterate over all elements in the KBuckets.
    *
    * @return an iterator
    */
  def iterator: Iterator[BitVector] = {
    nodeIds.iterator
  }

  /**
    * Remove an element by id.
    *
    * @param nodeId the nodeId to remove
    * @return
    */
  def remove(nodeId: BitVector): Boolean = {
    nodeIds.remove(nodeId)
  }

  override def toString: String = {
    s"KBuckets(baseId = ${baseId.toHex}): ${nodeIds.toList.map(id => s"(id=${id.toHex}, d=${Xor.d(id, baseId)})").mkString(", ")}"
  }
}
