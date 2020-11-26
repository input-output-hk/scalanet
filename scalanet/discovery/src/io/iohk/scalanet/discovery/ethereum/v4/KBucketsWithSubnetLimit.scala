package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.discovery.ethereum.Node
import io.iohk.scalanet.kademlia.{KBuckets, TimeSet}

case class KBucketsWithSubnetLimits[A](
    table: KBuckets[Hash],
    limits: KBucketsWithSubnetLimits.SubnetLimits
) {
  import DiscoveryNetwork.Peer

  def contains(peer: Peer[A]): Boolean =
    table.contains(peer.kademliaId)

  def touch(peer: Peer[A]): KBucketsWithSubnetLimits[A] =
    copy(table = table.touch(peer.kademliaId))

  def add(peer: Peer[A]): KBucketsWithSubnetLimits[A] =
    // TODO: Check limits
    copy(table = table.add(peer.kademliaId))

  def remove(peer: Peer[A]): KBucketsWithSubnetLimits[A] =
    copy(table = table.remove(peer.kademliaId))

  def closestNodes(targetKademliaId: Hash, n: Int): List[Hash] =
    table.closestNodes(targetKademliaId, n)

  def getBucket(peer: Peer[A]): (Int, TimeSet[Hash]) =
    table.getBucket(peer.kademliaId)
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

  def apply[A](
      node: Node,
      limits: SubnetLimits
  ): KBucketsWithSubnetLimits[A] =
    KBucketsWithSubnetLimits(new KBuckets[Hash](node.kademliaId, clock = java.time.Clock.systemUTC()), limits)
}
