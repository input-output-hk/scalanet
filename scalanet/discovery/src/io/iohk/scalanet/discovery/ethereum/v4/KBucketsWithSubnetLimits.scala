package io.iohk.scalanet.discovery.ethereum.v4

import cats._
import cats.implicits._
import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.discovery.ethereum.Node
import io.iohk.scalanet.kademlia.{KBuckets, TimeSet}
import io.iohk.scalanet.peergroup.Addressable
import io.iohk.scalanet.peergroup.InetAddressOps._
import java.net.InetAddress

case class KBucketsWithSubnetLimits[A: Addressable](
    table: KBuckets[Hash],
    limits: KBucketsWithSubnetLimits.SubnetLimits,
    tableLevelCounts: KBucketsWithSubnetLimits.TableLevelCounts,
    bucketLevelCounts: KBucketsWithSubnetLimits.BucketLevelCounts
) {
  import DiscoveryNetwork.Peer
  import KBucketsWithSubnetLimits._

  def contains(peer: Peer[A]): Boolean =
    table.contains(peer.kademliaId)

  def touch(peer: Peer[A]): KBucketsWithSubnetLimits[A] =
    // Note that `KBuckets.touch` also adds, so if the the record
    // isn't in the table already then use `add` to maintain counts.
    if (contains(peer)) copy(table = table.touch(peer.kademliaId)) else add(peer)

  /** Add the peer to the underlying K-table unless doing so would violate some limit. */
  def add(peer: Peer[A]): KBucketsWithSubnetLimits[A] =
    if (contains(peer)) this
    else {
      val ip = subnet(peer)
      val idx = getBucket(peer)._1

      // Upsert the counts of the index and/or IP in the maps, so that we can check the limits on them.
      val tlc = incrementForTable(ip)
      val blc = incrementForBucket(idx, ip)

      val isOverAnyLimit =
        limits.isOverLimitForTable(tlc(ip)) ||
          limits.isOverLimitForBucket(blc(idx)(ip))

      if (isOverAnyLimit) this
      else {
        copy(
          table = table.add(peer.kademliaId),
          tableLevelCounts = tlc,
          bucketLevelCounts = blc
        )
      }
    }

  def remove(peer: Peer[A]): KBucketsWithSubnetLimits[A] =
    if (!contains(peer)) this
    else {
      val ip = subnet(peer)
      val idx = getBucket(peer)._1

      val tlc = decrementForTable(ip)
      val blc = decrementForBucket(idx, ip)

      copy(table = table.remove(peer.kademliaId), tableLevelCounts = tlc, bucketLevelCounts = blc)
    }

  def closestNodes(targetKademliaId: Hash, n: Int): List[Hash] =
    table.closestNodes(targetKademliaId, n)

  def getBucket(peer: Peer[A]): (Int, TimeSet[Hash]) =
    table.getBucket(peer.kademliaId)

  private def subnet(peer: Peer[A]): InetAddress =
    Addressable[A].getAddress(peer.address).getAddress.truncate(limits.prefixLength)

  /** Increase the table level count for the IP of a subnet. */
  private def incrementForTable(ip: InetAddress): TableLevelCounts =
    tableLevelCounts |+| Map(ip -> 1)

  /** Increase the bucket level count for the IP of a subnet. */
  private def incrementForBucket(idx: Int, ip: InetAddress): BucketLevelCounts =
    bucketLevelCounts |+| Map(idx -> Map(ip -> 1))

  /** Decrement the table level count for the IP of a subnet and remove the entry if it's zero. */
  private def decrementForTable(ip: InetAddress): TableLevelCounts =
    tableLevelCounts |+| Map(ip -> -1) match {
      case counts if counts(ip) <= 0 => counts - ip
      case counts => counts
    }

  /** Decrement the bucket level count for the IP of a subnet and remove the entry if it's zero
    * for the subnet itself, or the whole bucket.
    */
  private def decrementForBucket(idx: Int, ip: InetAddress): BucketLevelCounts =
    bucketLevelCounts |+| Map(idx -> Map(ip -> -1)) match {
      case counts if counts(idx)(ip) <= 0 && counts(idx).size > 1 =>
        // The subnet count in the bucket is zero, but there are other subnets in the bucket,
        // so keep the bucket level count and just remove the subnet from it.
        counts.updated(idx, counts(idx) - ip)
      case counts if counts(idx)(ip) <= 0 =>
        // The subnet count is zero, and it's the only subnet in the bucket, so remove the bucket.
        counts - idx
      case counts =>
        counts
    }
}

object KBucketsWithSubnetLimits {
  type SubnetCounts = Map[InetAddress, Int]
  type TableLevelCounts = SubnetCounts
  type BucketLevelCounts = Map[Int, SubnetCounts]

  case class SubnetLimits(
      // Number of leftmost bits of the IP address that counts as a subnet, serving as its ID.
      prefixLength: Int,
      // Limit of nodes from the same subnet within any given bucket in the K-table.
      forBucket: Int,
      // Limit of nodes from the same subnet across all buckets in the K-table.
      forTable: Int
  ) {

    /** All limits can be disabled by setting the subnet prefix length to 0. */
    def isEnabled: Boolean = prefixLength > 0

    def isEnabledForBucket: Boolean =
      isEnabled && forBucket > 0

    def isEnabledForTable: Boolean =
      isEnabled && forTable > 0

    def isOverLimitForBucket(count: Int): Boolean =
      isEnabledForBucket && count > forBucket

    def isOverLimitForTable(count: Int): Boolean =
      isEnabledForTable && count > forTable
  }

  object SubnetLimits {
    val Unlimited = SubnetLimits(0, 0, 0)

    def fromConfig(config: DiscoveryConfig): SubnetLimits =
      SubnetLimits(
        prefixLength = config.subnetLimitPrefixLength,
        forBucket = config.subnetLimitForBucket,
        forTable = config.subnetLimitForTable
      )
  }

  def apply[A: Addressable](
      node: Node,
      limits: SubnetLimits
  ): KBucketsWithSubnetLimits[A] = {
    KBucketsWithSubnetLimits[A](
      new KBuckets[Hash](node.kademliaId, clock = java.time.Clock.systemUTC()),
      limits,
      tableLevelCounts = Map.empty[InetAddress, Int],
      bucketLevelCounts = Map.empty[Int, Map[InetAddress, Int]]
    )
  }
}
