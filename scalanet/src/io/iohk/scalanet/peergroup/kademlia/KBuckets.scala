package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock

import scodec.bits.BitVector

/**
  * Skeletal kbucket implementation.
  *
  * @param baseId the nodes own id.
  */
class KBuckets private (val baseId: BitVector, val clock: Clock, val buckets: List[TimeSet[BitVector]]) {

  def this(baseId: BitVector, clock: Clock) =
    this(baseId, clock, List.fill(baseId.length.toInt)(TimeSet[BitVector](clock)))

  /**
    * Find the n nodes closest to nodeId in kBuckets.
    * Return the resulting node records sorted by distance from the nodeId.
    * The indices into the kBuckets are defined by their distance from referenceNodeId.
    */
  def closestNodes(nodeId: BitVector, n: Int): List[BitVector] = {
    val ordering = new XorOrdering(nodeId)

    def loop(nodes: List[BitVector], iLeft: Int, iRight: Int): List[BitVector] = {
      if (nodes.size < n && (iLeft > -1 || iRight < buckets.size)) {

        val nodesNext = if (iLeft != iRight) {
          val lBucket = if (iLeft > -1) buckets(iLeft) else TimeSet.empty
          val rBucket = if (iRight < buckets.size) buckets(iRight) else TimeSet.empty
          lBucket ++ nodes ++ rBucket
        } else {
          buckets(iLeft) ++ nodes
        }

        loop(nodesNext.toList.sorted(ordering).take(n), iLeft - 1, iRight + 1)
      } else {
        nodes
      }
    }

    val i = if (nodeId == baseId) 0 else iBucket(nodeId)
    (baseId :: loop(Nil, i, i)).sorted(ordering).take(n)
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
      else {
        val (iBucket, bucket) = getBucket(nodeId)
        new KBuckets(baseId, clock, buckets.patch(iBucket, List(bucket + nodeId), 1))
      }
    } else {
      this
    }
  }

  /**
    * Query whether a given nodeId is present in the kbuckets.
    *
    * @return true if present
    */
  def contains(nodeId: BitVector): Boolean = {
    nodeId == baseId || buckets(iBucket(nodeId)).contains(nodeId)
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
  def remove(nodeId: BitVector): KBuckets = {
    if (nodeId == baseId)
      throw new UnsupportedOperationException("Cannot remove the baseId")
    else if (!contains(nodeId)) {
      this
    } else {
      val (iBucket, bucket) = getBucket(nodeId)
      new KBuckets(baseId, clock, buckets.patch(iBucket, List(bucket - nodeId), 1))
    }
  }

  override def toString: String = {
    s"KBuckets(baseId = ${baseId.toHex}):\n\t${buckets.indices.map(i => s"$i: ${bucketToString(buckets(i))}").mkString("\n\t")}"
  }

  def getBucket(b: BitVector): (Int, TimeSet[BitVector]) = {
    val i = iBucket(b)
    (i, buckets(i))
  }

  private def iBucket(b: BitVector): Int = {
    iBucket(Xor.d(b, baseId))
  }

  private def iBucket(b: BigInt): Int = {
    b.bitLength - 1
  }

  private def bucketToString(bucket: TimeSet[BitVector]): String = {
    s"${bucket.iterator.map(id => s"(id=${id.toBin}, d=${Xor.d(id, baseId)})").mkString(", ")}"
  }
}
