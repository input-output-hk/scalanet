package io.iohk.scalanet.peergroup.kademlia

import scodec.bits.BitVector

/**
  * Skeletal kbucket implementation.
  * @param baseId the nodes own id.
  */
class KBuckets(val baseId: BitVector) {

  private val buckets = (0 to baseId.length.toInt).map(_ => TimeSet[BitVector]()).toList

  add(baseId)

  /**
    * Find the n nodes closest to nodeId in kBuckets.
    * Return the resulting node records sorted by distance from the nodeId.
    * The indices into the kBuckets are defined by their distance from referenceNodeId.
    */
  def closestNodes(nodeId: BitVector, n: Int): List[BitVector] = {
    iterator.toList.sorted(new XorOrdering(nodeId)).take(n)
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

    bucket(nodeId).add(nodeId)

    this
  }

  /**
    * Query whether a given nodeId is present in the kbuckets.
    *
    * @return true if present
    */
  def contains(nodeId: BitVector): Boolean = {
    bucket(nodeId).contains(nodeId)
  }

  /**
    * Iterate over all elements in the KBuckets.
    *
    * @return an iterator
    */
  def iterator: Iterator[BitVector] = {
    buckets.flatten.iterator
  }

  /**
    * Remove an element by id.
    *
    * @param nodeId the nodeId to remove
    * @return
    */
  def remove(nodeId: BitVector): Boolean = {
    bucket(nodeId).remove(nodeId)
  }

  override def toString: String = {
    s"KBuckets(baseId = ${baseId.toHex}): ${this.iterator.map(id => s"(id=${id.toHex}, d=${Xor.d(id, baseId)})").mkString(", ")}"
  }

  private def iBucket(b: BitVector): Int = {
    iBucket(Xor.d(b, baseId))
  }

  private def iBucket(b: BigInt): Int = {
    import BigIntExtentions._
    (b + 1).log2.toInt
  }

  private def bucket(b: BitVector): TimeSet[BitVector] = {
    buckets(iBucket(b))
  }
}
