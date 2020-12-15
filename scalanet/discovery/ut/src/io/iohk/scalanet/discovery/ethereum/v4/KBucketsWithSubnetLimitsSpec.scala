package io.iohk.scalanet.discovery.ethereum.v4

import org.scalatest._
import java.net.InetSocketAddress
import io.iohk.scalanet.discovery.ethereum.v4.KBucketsWithSubnetLimits.SubnetLimits
import io.iohk.scalanet.discovery.ethereum.Node
import io.iohk.scalanet.discovery.hash.Keccak256
import java.net.InetAddress
import io.iohk.scalanet.discovery.ethereum.v4.DiscoveryNetwork.Peer
import scodec.bits.BitVector
import io.iohk.scalanet.discovery.crypto.PublicKey

class KBucketsWithSubnetLimitsSpec extends FlatSpec with Matchers with Inspectors {

  // For the tests I only care about the IP addresses; a 1-to-1 mapping is convenient.
  def fakeNodeId(address: InetAddress): Node.Id =
    PublicKey(Keccak256(BitVector(address.getAddress)))

  def makeNode(address: InetSocketAddress) =
    Node(fakeNodeId(address.getAddress), Node.Address(address.getAddress, address.getPort, address.getPort))

  def makePeer(address: InetAddress, port: Int = 30303) =
    Peer[InetSocketAddress](id = fakeNodeId(address), address = new InetSocketAddress(address, port))

  def makeIp(name: String) = InetAddress.getByName(name)

  val localNode = makeNode(new InetSocketAddress("127.0.0.1", 30303))
  val defaultLimits = SubnetLimits(prefixLength = 24, forBucket = 2, forTable = 10)

  trait Fixture {
    lazy val limits = defaultLimits
    lazy val ips: Vector[String] = Vector.empty
    lazy val peers = ips.map(ip => makePeer(makeIp(ip)))
    lazy val kBuckets = peers.foldLeft(KBucketsWithSubnetLimits(localNode, limits = limits))(_.add(_))
  }

  behavior of "KBucketsWithSubnetLimits"

  it should "increment the count of the subnet after add" in new Fixture {
    override lazy val ips = Vector("5.67.8.9", "5.67.8.10", "5.67.1.2")
    val subnet = makeIp("5.67.8.0")
    val idx = kBuckets.getBucket(peers.head)._1
    kBuckets.tableLevelCounts(subnet) shouldBe 2
    kBuckets.tableLevelCounts.values should contain theSameElementsAs List(2, 1)
    kBuckets.bucketLevelCounts(idx)(subnet) shouldBe >=(1)
  }

  it should "not increment the count if the peer is already in the table" in new Fixture {
    override lazy val ips = Vector("5.67.8.9", "5.67.8.9", "5.67.8.9")
    val subnet = makeIp("5.67.8.0")
    val idx = kBuckets.getBucket(peers.head)._1
    kBuckets.tableLevelCounts(subnet) shouldBe 1
    kBuckets.bucketLevelCounts(idx)(subnet) shouldBe 1
  }

  it should "decrement the count after removal" in new Fixture {
    override lazy val ips = Vector("5.67.8.9", "5.67.8.10")

    val removed0 = kBuckets.remove(peers(0))
    removed0.tableLevelCounts.values.toList shouldBe List(1)
    removed0.bucketLevelCounts.values.toList shouldBe List(Map(makeIp("5.67.8.0") -> 1))

    val removed1 = removed0.remove(peers(1))
    removed1.tableLevelCounts shouldBe empty
    removed1.bucketLevelCounts shouldBe empty
  }

  it should "not decrement if the peer is not in the table" in new Fixture {
    override lazy val ips = Vector("1.2.3.4")
    val removed = kBuckets.remove(makePeer(makeIp("1.2.3.5")))
    kBuckets.tableLevelCounts should not be empty
    kBuckets.bucketLevelCounts should not be empty
  }

  it should "not add IP if it violates the limits" in new Fixture {
    override lazy val ips = Vector.range(0, defaultLimits.forTable + 1).map(i => s"192.168.1.$i")

    forAll(peers.take(defaultLimits.forBucket)) { peer =>
      kBuckets.contains(peer) shouldBe true
    }

    forAtLeast(1, peers) { peer =>
      kBuckets.contains(peer) shouldBe false
    }

    forAll(peers) { peer =>
      val (_, bucket) = kBuckets.getBucket(peer)
      bucket.size shouldBe <=(defaultLimits.forBucket)
    }
  }

  it should "treat limits separately per subnet" in new Fixture {
    override lazy val ips = Vector.range(0, 256).map { i =>
      s"192.168.1.$i"
    } :+ "192.168.2.1"

    kBuckets.contains(peers.last) shouldBe true
  }

  it should "add peers after removing previous ones" in new Fixture {
    override lazy val ips = Vector.range(0, 255).map(i => s"192.168.1.$i")

    kBuckets.tableLevelCounts.values.toList shouldBe List(defaultLimits.forTable)

    val peer = makePeer(makeIp("192.168.1.255"))
    kBuckets.add(peer).contains(peer) shouldBe false
    kBuckets.remove(peer).add(peer).contains(peer) shouldBe false
    kBuckets.remove(peers.head).add(peer).contains(peer) shouldBe true
  }

  it should "not use limits if the prefix is 0" in new Fixture {
    override lazy val limits = defaultLimits.copy(prefixLength = 0)
    override lazy val ips = Vector.range(0, 256).map(i => s"192.168.1.$i")

    kBuckets.tableLevelCounts.values.toList shouldBe List(256)
  }

  it should "not use limits if the table level limit is 0, but still apply the bucket limit" in new Fixture {
    override lazy val limits = defaultLimits.copy(forTable = 0)
    override lazy val ips = Vector.range(0, 256).map(i => s"192.168.1.$i")

    kBuckets.tableLevelCounts.values.toList.head shouldBe >(defaultLimits.forTable)
    forAll(peers) { peer =>
      val (i, _) = kBuckets.getBucket(peer)
      kBuckets.bucketLevelCounts(i).values.head shouldBe <=(defaultLimits.forBucket)
    }
  }

  it should "not limit buckets if the bucket level limit is 0" in new Fixture {
    override lazy val limits = defaultLimits.copy(forBucket = 0)
    override lazy val ips = Vector.range(0, 256).map(i => s"192.168.1.$i")

    kBuckets.tableLevelCounts.values.toList shouldBe List(limits.forTable)
    forAtLeast(1, peers) { peer =>
      val (i, _) = kBuckets.getBucket(peer)
      kBuckets.bucketLevelCounts(i).values.head shouldBe >(defaultLimits.forBucket)
    }
  }
}
