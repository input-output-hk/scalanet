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
    tableLevelCounts: Map[InetAddress, Int],
    bucketLevelCounts: Map[Int, Map[InetAddress, Int]]
) {
  import DiscoveryNetwork.Peer

  def contains(peer: Peer[A]): Boolean =
    table.contains(peer.kademliaId)

  def touch(peer: Peer[A]): KBucketsWithSubnetLimits[A] =
    copy(table = table.touch(peer.kademliaId))

  def add(peer: Peer[A]): KBucketsWithSubnetLimits[A] =
    if (contains(peer)) this
    else {
      val ip = subnet(peer)
      val idx = getBucket(peer)._1

      val tlc = tableLevelCounts |+| Map(ip -> 1)
      val blc = bucketLevelCounts |+| Map(idx -> Map(ip -> 1))

      val overTheLimit =
        limits.prefixLength > 0 && (
          limits.forTable > 0 && tlc(ip) > limits.forTable ||
            limits.forBucket > 0 && blc(idx)(ip) > limits.forBucket
        )

      if (overTheLimit) this
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

      val tlc = tableLevelCounts |+| Map(ip -> -1) match {
        case counts if counts(ip) <= 0 => counts - ip
        case counts => counts
      }
      val blc = bucketLevelCounts |+| Map(idx -> Map(ip -> -1)) match {
        case counts if counts(idx)(ip) <= 0 && counts(idx).size > 1 =>
          counts.updated(idx, counts(idx) - ip)
        case counts if counts(idx)(ip) <= 0 =>
          counts - idx
        case counts =>
          counts
      }
      copy(table = table.remove(peer.kademliaId), tableLevelCounts = tlc, bucketLevelCounts = blc)
    }

  def closestNodes(targetKademliaId: Hash, n: Int): List[Hash] =
    table.closestNodes(targetKademliaId, n)

  def getBucket(peer: Peer[A]): (Int, TimeSet[Hash]) =
    table.getBucket(peer.kademliaId)

  private def subnet(peer: Peer[A]): InetAddress =
    Addressable[A].getAddress(peer.address).getAddress.truncate(limits.prefixLength)
}

object KBucketsWithSubnetLimits {
  case class SubnetLimits(prefixLength: Int, forBucket: Int, forTable: Int)

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
