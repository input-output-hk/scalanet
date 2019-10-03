package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock

import scodec.bits.BitVector

/**
  * Skeletal kbucket implementation.
  * @param baseId the nodes own id.
  */
class KBuckets(val baseId: BitVector, val clock: Clock) {

  private val buckets = (0 until baseId.length.toInt).map(_ => TimeSet[BitVector](clock)).toList

  /**
    * Find the n nodes closest to nodeId in kBuckets.
    * Return the resulting node records sorted by distance from the nodeId.
    * The indices into the kBuckets are defined by their distance from referenceNodeId.
    */
  def closestNodes(nodeId: BitVector, n: Int): List[BitVector] = {
    iterator.toList.sorted(new XorOrdering(nodeId)).take(n)
  }

  /**
    * Add a node record into the KBuckets.
    *
    * @return this KBuckets instance.
    */
  def add(nodeId: BitVector): KBuckets = {
    if (nodeId != baseId) {
      if (nodeId.length != this.baseId.length)
        throw new IllegalArgumentException(
          s"Illegal attempt to add node id with a length different than the this node id."
        )

      bucket(nodeId).add(nodeId)
    }
    this
  }

  /**
    * Query whether a given nodeId is present in the kbuckets.
    *
    * @return true if present
    */
  def contains(nodeId: BitVector): Boolean = {
    if (nodeId == baseId)
      true
    else
      bucket(nodeId).contains(nodeId)
  }

  /**
    * Iterate over all elements in the KBuckets.
    *
    * @return an iterator
    */
  private def iterator: Iterator[BitVector] = {
    (baseId :: buckets.flatten).iterator
  }

  /**
    * Remove an element by id.
    *
    * @param nodeId the nodeId to remove
    * @return
    */
  def remove(nodeId: BitVector): Boolean = {
    if (nodeId == baseId)
      throw new UnsupportedOperationException("Cannot remove the baseId")
    bucket(nodeId).remove(nodeId)
  }

  def iBucket(b: BitVector): Int = {
    iBucket(Xor.d(b, baseId))
  }

  def bucket(iBucket: Int): TimeSet[BitVector] = {
    buckets(iBucket)
  }

  private def bucket(b: BitVector): TimeSet[BitVector] = {
    buckets(iBucket(b))
  }

  override def toString: String = {
    s"KBuckets(baseId = ${baseId.toHex}):\n\t${buckets.indices.map(i => s"$i: ${bucketToString(buckets(i))}").mkString("\n\t")}"
  }

  private def bucketToString(bucket: TimeSet[BitVector]): String = {
    s"${bucket.iterator.map(id => s"(id=${id.toBin}, d=${Xor.d(id, baseId)})").mkString(", ")}"
  }

  def iBucket(b: BigInt): Int = {
    import BigIntExtentions._
    b.log2.toInt
  }
}
