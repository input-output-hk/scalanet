package io.iohk.scalanet.peergroup.kademlia

import java.time.Clock
import java.util.Random

import scodec.bits.BitVector

/**
 *
 * @param baseId the nodes own id.
 * @param clock clock required to keep track of last usage of id in particular bucket
 * @param buckets list of buckets, each bucket is sorted form least recently seen node id at head and most recently seen
 *                at the tail
 */
class KBuckets private (
                         val baseId: BitVector,
                         val clock: Clock,
                         val buckets: IndexedSeq[TimeSet[BitVector]]
                       ) {

  def this(baseId: BitVector, clock: Clock) =
    this(baseId, clock, IndexedSeq.fill(baseId.length.toInt)(TimeSet[BitVector](clock)))

  /**
   * Find the n nodes closest to nodeId in kBuckets.
   * Return the resulting node records sorted by distance from the nodeId.
   * The indices into the kBuckets are defined by their distance from referenceNodeId.
   */
  def closestNodes(nodeId: BitVector, n: Int): List[BitVector] = {
    val ordering = new XorOrdering(nodeId)

    // returns ordered buckets: if bucket comes first in the result
    // then all of its elements are closer to nodeId that any element
    // from buckets further in the stream
    // virtual one-element bucket with baseId is added
    def orderedBucketsStream: Stream[Seq[BitVector]] = {
      // That part is simple in implementation but complex conceptually. It bases on observation that buckets can
      // be ordered by closeness to nodeId, i.e. for buckets A and B either all elements its elements are closer
      // to nodeId than any element of B or all elements from A are farther from nodeId than any element from B.
      //
      // To people with maths background: it means that when we treat all know nodes as an ordered set, then
      // we can treat function assigning each node a bucket as totally ordered set homomorphism - with induced
      // order on buckets, i.e. if x <= y then b(x) <= b(y).
      //
      // There is a sequence of observations leading to this algorithm. Let m be the bit length of stored values
      // (or equivalently the number of buckets). When we say i-th bucket we mean 0-based indexing, with i-th bucket
      // containing values with XOR metrics distance from baseId in range [2^i, 2^(i + 1)), while speaking about
      // i-th bit we mean 1-based indexing of m-length bit vector.
      //
      // 1. Values in i-th bucket share (m - i - 1)-bit prefix with baseId and differ on (m - i)-th position
      // 2. Values from i-th bucket and j-th bucket with j < i share (m - i - 1)-bit prefix with baseId ones
      //    from the i-th bucket differ with baseId on (m - i)-th position, while elements from the j-th bucket agree
      //    with baseId on (m - i)-th position
      // 3. Values from i-th bucket xorred with nodeId have common (m - i - 1)-bit prefix with (nodeId xor baseId);
      //    so do values from j-th bucket for j < i. On (m - i)-th position i-th bucket values xorred with nodeId
      //    have different bit than (nodeId xor baseId) while values from j-th bucket have the same
      // 4. Because of that the XOR metric distance of any value in i-th bucket and any value in j-th bucket
      //    for j < i are in relation determined by (m - i)-th position of (nodeId xor baseId).
      //    This is because both XOR metric distances have (m - i - 1)-bit common prefix and differ on (m - i)-th bit;
      //    if (m - i)-th bit of (nodeId xor baseId) is 1 then the element from i-th bucket is closer to nodeId,
      //    otherwise the element from j-th bucket is
      // 5. To obtain correct bucket order, we should start with empty queue and iterate over all buckets.
      //    If for i-th bucket the corresponding bit of (nodeId xor baseId) - (m - i)-th one - is 1, we should push
      //    the bucket to the front (as its elements are closer to nodeId than any element from j-th bucket for j < i)
      //    and push it to the back if it is 0 (as its elements are farther from nodeId).
      // 6. When we analyse what the resulting queue is going to look like, we'll notice, that first we'll get buckets
      //    corresponding to bits where (nodeId xor baseId) is 1, in reverse order and then buckets corresponding to
      //    bits where (nodeId xor baseId) is 0 is normal order
      // 7. Moving to 0-based indexing: bit corresponding to buckets(i), the i-th bucket, is (m - i)-th bit,
      //    so (nodeId xor baseId)(m - i - 1); it is 1 if and only if nodeId(m - i - 1) != baseId(m - i - 1)
      // 8. There is still baseId which isn't stored it buckets; we can think of if as residing in one element
      //    virtual bucket before all real buckets. We can just push such artificial bucket to the queue before
      //    starting our iteration

      // buckets with elements closer to nodeId that baseId, sorted appropriately
      def closerBuckets: Stream[Seq[BitVector]] =
        Stream
          .range(buckets.size - 1, -1, -1)
          .filter(i => nodeId(buckets.size - i - 1) != baseId(buckets.size - i - 1))
          .map(i => buckets(i).toSeq)

      // buckets with elements farther from nodeId than baseId, sorted appropriately
      def furtherBuckets: Stream[Seq[BitVector]] =
        Stream
          .range(0, buckets.size, 1)
          .filter(i => nodeId(buckets.size - i - 1) == baseId(buckets.size - i - 1))
          .map(i => buckets(i).toSeq)

      closerBuckets ++ (Seq(baseId) #:: furtherBuckets)
    }

    if (n == 1) {
      // special case to avoid sorting the bucket
      orderedBucketsStream.find(_.nonEmpty).map(_.min(ordering)).toList
    } else {
      orderedBucketsStream.flatMap(_.sorted(ordering)).take(n).toList
    }
  }

  /**
   * Add a node record into the KBuckets.
   *
   * @return this KBuckets instance.
   */
  def add(nodeId: BitVector): KBuckets = {
    bucketOp(nodeId)((iBucket, bucket) => new KBuckets(baseId, clock, buckets.patch(iBucket, List(bucket + nodeId), 1)))
  }

  /**
   * Move a given nodeId to the tail of its respective bucket.
   * @param nodeId the nodeId to touch
   * @return
   */
  def touch(nodeId: BitVector): KBuckets = {
    bucketOp(nodeId) { (iBucket, bucket) =>
      new KBuckets(baseId, clock, buckets.patch(iBucket, List(bucket.touch(nodeId)), 1))
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

  private def bucketOp(nodeId: BitVector)(op: (Int, TimeSet[BitVector]) => KBuckets): KBuckets = {
    if (nodeId != baseId) {
      if (nodeId.length != this.baseId.length)
        throw new IllegalArgumentException(
          s"Illegal node id '${nodeId.toHex}' has bit length ${nodeId.size} but requires length ${baseId.size}."
        )
      else {
        val (iBucket, bucket) = getBucket(nodeId)
        op(iBucket, bucket)
      }
    } else {
      this
    }
  }
}

object KBuckets {
  def generateRandomId(length: Long, rnd: Random): BitVector = {
    BitVector.bits(Range.Long(0, length, 1).map(_ => rnd.nextBoolean()))
  }
}