package io.iohk.scalanet.discovery.ethereum.v4

import cats.implicits._
import cats.effect.concurrent.Ref
import io.iohk.scalanet.discovery.crypto.{PublicKey, Signature}
import io.iohk.scalanet.discovery.ethereum.{EthereumNodeRecord, Node}
import io.iohk.scalanet.discovery.ethereum.codecs.DefaultCodecs
import io.iohk.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import io.iohk.scalanet.discovery.ethereum.v4.DiscoveryNetwork.Peer
import io.iohk.scalanet.kademlia.Xor
import io.iohk.scalanet.NetUtils.aRandomAddress
import java.net.InetSocketAddress
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import org.scalatest._
import scala.concurrent.duration._
import scala.util.Random
import java.net.InetAddress

class DiscoveryServiceSpec extends AsyncFlatSpec with Matchers {
  import DiscoveryService.{State, BondingResults}
  import DiscoveryServiceSpec._
  import DefaultCodecs._

  def test(fixture: Fixture) =
    fixture.test.timeout(15.seconds).runToFuture

  behavior of "isBonded"

  trait IsBondedFixture extends Fixture {
    override val test = for {
      _ <- stateRef.update(setupState)
      isBonded <- service.isBonded(peer)
    } yield {
      isBonded shouldBe expected
    }

    def setupState: State[InetSocketAddress] => State[InetSocketAddress] = identity
    def peer: Peer[InetSocketAddress]
    def expected: Boolean
  }

  it should "return true for self" in test {
    new IsBondedFixture {
      override def peer = localPeer
      override def expected = true
    }
  }
  it should "return false for unknown nodes" in test {
    new IsBondedFixture {
      override def peer = remotePeer
      override def expected = false
    }
  }
  it should "return true for nodes that responded to pongs within the expiration period" in test {
    new IsBondedFixture {
      override def peer = remotePeer
      override def expected = true
      override def setupState = _.withLastPongTimestamp(remotePeer, System.currentTimeMillis)
    }
  }
  it should "return false for nodes that responded to pongs earlier than the expiration period" in test {
    new IsBondedFixture {
      override def peer = remotePeer
      override def expected = false
      override def setupState =
        _.withLastPongTimestamp(remotePeer, System.currentTimeMillis - config.bondExpiration.toMillis - 1000)
    }
  }
  it should "return true for nodes that are being pinged right now but responded within expiration" in test {
    new IsBondedFixture {
      override def peer = remotePeer
      override def expected = true
      override def setupState =
        _.withBondingResults(remotePeer, BondingResults.unsafe())
          .withLastPongTimestamp(remotePeer, System.currentTimeMillis - config.bondExpiration.toMillis + 1000)
    }
  }
  it should "return false for nodes that are being pinged right now but are otherwise expired" in test {
    new IsBondedFixture {
      override def peer = remotePeer
      override def expected = false
      override def setupState =
        _.withBondingResults(remotePeer, BondingResults.unsafe())
          .withLastPongTimestamp(remotePeer, System.currentTimeMillis - config.bondExpiration.toMillis - 1000)
    }
  }
  it should "return false for nodes that changed their address" in test {
    new IsBondedFixture {
      override def peer = Peer(remotePublicKey, aRandomAddress)
      override def expected = false
      override def setupState =
        _.withLastPongTimestamp(
          Peer(remotePublicKey, remoteAddress),
          System.currentTimeMillis
        )
    }
  }

  behavior of "initBond"

  it should "return a the current ENR sequence if there's no current bonding running" in test {
    new Fixture {
      override val test = for {
        maybeExistingResults <- service.initBond(remotePeer)
      } yield {
        maybeExistingResults shouldBe empty
      }
    }
  }
  it should "return the existing deferred result if bonding is already running" in test {
    new Fixture {
      override val test = for {
        _ <- service.initBond(remotePeer)
        maybeExistingResults <- service.initBond(remotePeer)
      } yield {
        maybeExistingResults should not be empty
      }
    }
  }

  behavior of "completePong"

  trait InitBondFixture extends Fixture {
    def responded: Boolean
    override val test = for {
      _ <- service.initBond(remotePeer)
      pongReceived <- stateRef.get.map { state =>
        state.bondingResultsMap(remotePeer).pongReceived
      }
      _ <- service.completePong(remotePeer, responded = responded)
      state <- stateRef.get
      bonded <- pongReceived.get
    } yield {
      bonded shouldBe responded
      state.bondingResultsMap.get(remotePeer) shouldBe empty
    }
  }

  it should "complete the deferred with true if the peer respond" in test {
    new InitBondFixture {
      override def responded = true
    }
  }
  it should "complete the deferred with false if did not respond" in test {
    new InitBondFixture {
      override def responded = false
    }
  }

  behavior of "awaitPing"

  it should "wait up to the request timeout if there's no ping" in test {
    new Fixture {
      override lazy val config = defaultConfig.copy(
        requestTimeout = 200.millis
      )
      override val test = for {
        _ <- service.initBond(remotePeer)
        time0 <- service.currentTimeMillis
        _ <- service.awaitPing(remotePeer)
        time1 <- service.currentTimeMillis
      } yield {
        assert(time1 - time0 >= config.requestTimeout.toMillis)
      }
    }
  }

  it should "complete as soon as there's a ping" in test {
    new Fixture {
      override lazy val config = defaultConfig.copy(
        requestTimeout = 1.second
      )
      override val test = for {
        _ <- service.initBond(remotePeer)
        time0 <- service.currentTimeMillis
        waiting <- service.awaitPing(remotePeer).start
        pingReceived <- stateRef.get.map { state =>
          state.bondingResultsMap(remotePeer).pingReceived
        }
        _ <- pingReceived.complete(())
        _ <- waiting.join
        time1 <- service.currentTimeMillis
      } yield {
        assert(time1 - time0 < config.requestTimeout.toMillis)
      }
    }
  }

  behavior of "completePing"

  it should "complete the expected ping" in test {
    new Fixture {
      override val test = for {
        _ <- service.initBond(remotePeer)
        pingReceived <- stateRef.get.map { state =>
          state.bondingResultsMap(remotePeer).pingReceived
        }
        _ <- service.completePing(remotePeer)
        _ <- pingReceived.get.timeout(1.second)
      } yield {
        // It would time out if it wasn't completed.
        succeed
      }
    }
  }

  it should "ignore subsequent pings" in test {
    new Fixture {
      override val test = for {
        _ <- service.initBond(remotePeer)
        _ <- service.completePing(remotePeer)
        _ <- service.completePing(remotePeer)
      } yield {
        // It's enough that it didn't fail due to multiple completions.
        succeed
      }
    }
  }

  it should "ignore peers which weren't expected" in test {
    new Fixture {
      override val test = for {
        _ <- service.completePing(remotePeer)
      } yield {
        succeed
      }
    }
  }

  behavior of "bond"

  it should "not try to bond if already bonded" in test {
    new Fixture {
      override val test = for {
        _ <- stateRef.update { state =>
          state.withLastPongTimestamp(remotePeer, System.currentTimeMillis)
        }
        bonded <- service.bond(remotePeer)
      } yield {
        bonded shouldBe true
      }
    }
  }

  trait BondingFixture extends Fixture {
    override lazy val rpc = unimplementedRPC.copy(
      ping = _ => _ => Task.pure(Some(None)),
      enrRequest = _ => _ => Task.pure(Some(remoteENR))
    )
  }

  it should "consider a peer bonded if it responds to a ping" in test {
    new BondingFixture {
      override val test = for {
        bonded <- service.bond(remotePeer)
        state <- stateRef.get
      } yield {
        bonded shouldBe true
        state.bondingResultsMap should not contain key(remotePeer)
        state.lastPongTimestampMap should contain key (remotePeer)
      }
    }
  }

  it should "not consider a peer bonded if it doesn't respond to a ping" in test {
    new BondingFixture {
      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(None)
      )
      override val test = for {
        bonded <- service.bond(remotePeer)
        state <- stateRef.get
      } yield {
        bonded shouldBe false
        state.bondingResultsMap should not contain key(remotePeer)
        state.lastPongTimestampMap should not contain key(remotePeer)
      }
    }
  }

  it should "wait for a ping to arrive from the other party" in test {
    new BondingFixture {
      override lazy val config = defaultConfig.copy(
        requestTimeout = 5.seconds
      )
      override val test = for {
        time0 <- service.currentTimeMillis
        bonding <- service.bond(remotePeer).start
        // Simulating a Ping from the remote.
        _ <- service.completePing(remotePeer).delayExecution(50.millis)
        bonded <- bonding.join
        time1 <- service.currentTimeMillis
      } yield {
        bonded shouldBe true
        // We shouldn't need to wait for a full timeout since the
        // ping from the remote peer should arrive quicker.
        assert(time1 - time0 < config.requestTimeout.toMillis)
      }
    }
  }

  it should "fetch the ENR once bonded" in test {
    new BondingFixture {
      override val test = for {
        _ <- service.bond(remotePeer)
        // Allow the ENR fetching to finish.
        _ <- stateRef.get.flatMap(_.fetchEnrMap.get(remotePeer).fold(Task.sleep(100.millis))(_.get.void))
        state <- stateRef.get
      } yield {
        state.enrMap(remotePublicKey) shouldBe remoteENR
        state.nodeMap(remotePublicKey) shouldBe remoteNode
      }
    }
  }

  it should "remove the peer if the bond fails" in test {
    new BondingFixture {
      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(None)
      )
      override val test = for {
        _ <- stateRef.update {
          _.withEnrAndAddress(remotePeer, remoteENR, remoteNode.address)
            .withLastPongTimestamp(remotePeer, System.currentTimeMillis - config.bondExpiration.toMillis * 2)
        }
        _ <- service.bond(remotePeer)
        state <- stateRef.get
      } yield {
        state.enrMap should not contain key(remotePublicKey)
        state.lastPongTimestampMap should not contain key(remotePeer)
      }
    }
  }

  behavior of "maybeFetchEnr"

  it should "not fetch if the record we have is at least as new" in test {
    new Fixture {
      override val test = for {
        _ <- stateRef.update(_.withEnrAndAddress(remotePeer, remoteENR, remoteNode.address))
        _ <- service.maybeFetchEnr(remotePeer, Some(remoteENR.content.seq))
      } yield {
        succeed // Would have failed if it called the RPC.
      }
    }
  }

  it should "fetch if the address changed" in test {
    new Fixture {
      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(Some(None)),
        enrRequest = _ => _ => Task(Some(remoteENR))
      )
      val previousAddress = aRandomAddress
      val previousNode = makeNode(remotePublicKey, previousAddress)
      // Say it had the same ENR SEQ, but a different address.
      val previousEnr = EthereumNodeRecord.fromNode(previousNode, remotePrivateKey, seq = remoteENR.content.seq).require
      val previousPeer = Peer(remotePublicKey, previousAddress)

      override val test = for {
        // Pretend we know of a different address for this node.
        _ <- stateRef.update {
          _.withEnrAndAddress(previousPeer, previousEnr, previousNode.address)
        }
        _ <- service.maybeFetchEnr(remotePeer, Some(previousEnr.content.seq))
        state <- stateRef.get
      } yield {
        state.enrMap(remotePublicKey) shouldBe remoteENR
      }
    }
  }

  behavior of "fetchEnr"

  it should "only initiate one fetch at a time" in test {
    new Fixture {
      val callCount = AtomicInt(0)

      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(Some(None)),
        enrRequest = _ =>
          _ =>
            Task {
              callCount.increment()
              Some(remoteENR)
            }.delayExecution(100.millis) // Delay so the first is still running when the second is started.
      )

      override val test = for {
        _ <- Task.parSequenceUnordered(
          List.fill(5)(service.fetchEnr(remotePeer))
        )
      } yield {
        callCount.get shouldBe 1
      }
    }
  }

  it should "update the ENR, node maps and the k-buckets" in test {
    new Fixture {
      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(Some(None)),
        enrRequest = _ => _ => Task(Some(remoteENR))
      )
      override val test = for {
        _ <- service.fetchEnr(remotePeer)
        state <- stateRef.get
      } yield {
        state.fetchEnrMap should not contain key(remotePeer)
        state.nodeMap should contain key (remotePeer.id)
        state.enrMap(remotePeer.id) shouldBe remoteENR
        state.kBuckets.contains(remotePeer.kademliaId) shouldBe true
      }
    }
  }

  it should "remove the node if the ENR signature validation fails" in test {
    new Fixture {
      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(Some(None)),
        enrRequest = _ => _ => Task(Some(remoteENR.copy(signature = Signature(remoteENR.signature.reverse))))
      )
      override val test = for {
        _ <- stateRef.update(_.withEnrAndAddress(remotePeer, remoteENR, remoteNode.address))
        _ <- service.fetchEnr(remotePeer)
        state <- stateRef.get
      } yield {
        state.fetchEnrMap should not contain key(remotePeer)
        state.nodeMap should not contain key(remotePeer.id)
        state.enrMap should not contain key(remotePeer.id)
      }
    }
  }

  it should "not remove the bonded status if the ENR fetch fails" in test {
    new Fixture {
      override lazy val rpc = unimplementedRPC.copy(
        enrRequest = _ => _ => Task(None)
      )
      override val test = for {
        _ <- stateRef.update(_.withLastPongTimestamp(remotePeer, System.currentTimeMillis))
        _ <- service.fetchEnr(remotePeer)
        state <- stateRef.get
      } yield {
        state.lastPongTimestampMap should contain key (remotePeer)
      }
    }
  }

  behavior of "storePeer"

  trait FullBucketFixture extends Fixture {
    override lazy val config = defaultConfig.copy(
      kademliaBucketSize = 1
    )
    // Make two peers that don't share the first bit in their Kademlia ID with the local one.
    // These will share the same k-bucket.
    def makePeerInFirstBucket: (Peer[InetSocketAddress], EthereumNodeRecord, Node.Address) = {
      val (publicKey, privateKey) = sigalg.newKeyPair
      if (Node.kademliaId(publicKey)(0) == Node.kademliaId(localPublicKey)(0))
        makePeerInFirstBucket
      else {
        val address = aRandomAddress
        val node = makeNode(publicKey, address)
        val peer = Peer(publicKey, address)
        val enr = EthereumNodeRecord.fromNode(node, privateKey, seq = 1).require
        (peer, enr, node.address)
      }
    }
    val peer1 = makePeerInFirstBucket
    val peer2 = makePeerInFirstBucket

    def responds: Boolean

    // We'll try to ping the first peer.
    override lazy val rpc = unimplementedRPC.copy(
      ping = _ => _ => Task.pure(if (responds) Some(None) else None)
    )
    override val test = for {
      _ <- stateRef.update(
        _.withEnrAndAddress(peer1._1, peer1._2, peer1._3)
      )
      _ <- service.storePeer(peer2._1, peer2._2, peer2._3)
      state <- stateRef.get
    } yield {
      // If the existing peer didn't respond, forget them completely.
      state.nodeMap.contains(peer1._1.id) shouldBe responds
      state.enrMap.contains(peer1._1.id) shouldBe responds
      state.kBuckets.contains(peer1._1.kademliaId) shouldBe responds

      // Add the new ENR of the peer regardless of the existing.
      state.nodeMap.contains(peer2._1.id) shouldBe true
      state.enrMap.contains(peer2._1.id) shouldBe true
      // Only use them for routing if the existing got evicted.
      state.kBuckets.contains(peer2._1.kademliaId) shouldBe !responds
    }
  }

  it should "evict the oldest peer if the bucket is full and the peer is not responding" in test {
    new FullBucketFixture {
      val responds = false
    }
  }

  it should "not evict the oldest peer if it still responds" in test {
    new FullBucketFixture {
      val responds = true
    }
  }

  behavior of "ping"

  it should "respond with the ENR sequence but not bond with the caller before enrollment" in test {
    new Fixture {
      override val test = for {
        hasEnrolled <- stateRef.get.map(_.hasEnrolled)
        maybeEnrSeq <- service.ping(remotePeer)(None)
        // Shouldn't start bonding, but in case it does, wait until it finishes.
        _ <- stateRef.get.flatMap(_.bondingResultsMap.get(remotePeer).fold(Task.unit)(_.pongReceived.get.void))
        state <- stateRef.get
      } yield {
        hasEnrolled shouldBe false
        maybeEnrSeq shouldBe Some(Some(localENR.content.seq))
        state.nodeMap should not contain key(remotePeer.id)
        state.enrMap should not contain key(remotePeer.id)
        state.lastPongTimestampMap should not contain key(remotePeer)
      }
    }
  }

  it should "respond with the ENR sequence and bond with the caller after enrollment" in test {
    new Fixture {
      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task(Some(None)),
        enrRequest = _ => _ => Task(Some(remoteENR))
      )
      override val test = for {
        _ <- stateRef.update(_.setEnrolled)
        maybeEnrSeq <- service.ping(remotePeer)(None)
        // Wait for any ongoing bonding and ENR fetching to finish.
        _ <- stateRef.get.flatMap(_.bondingResultsMap.get(remotePeer).fold(Task.unit)(_.pongReceived.get.void))
        _ <- stateRef.get.flatMap(_.fetchEnrMap.get(remotePeer).fold(Task.unit)(_.get.void))
        state <- stateRef.get
      } yield {
        maybeEnrSeq shouldBe Some(Some(localENR.content.seq))
        state.nodeMap(remotePeer.id) shouldBe remoteNode
        state.enrMap(remotePeer.id) shouldBe remoteENR
        state.lastPongTimestampMap should contain key (remotePeer)
      }
    }
  }

  behavior of "findNode"

  it should "not respond to unbonded peers" in test {
    new Fixture {
      override val test = for {
        maybeResponse <- service.findNode(remotePeer)(remotePublicKey)
      } yield {
        maybeResponse shouldBe empty
      }
    }
  }

  it should "return peers for who we have an ENR record" in test {
    new Fixture {
      val caller = {
        val (callerPublicKey, _) = sigalg.newKeyPair
        val callerAddress = aRandomAddress
        Peer(callerPublicKey, callerAddress)
      }

      override val test = for {
        // Pretend we are bonded with the caller and know about the remote node.
        _ <- stateRef.update {
          _.withLastPongTimestamp(caller, System.currentTimeMillis)
            .withEnrAndAddress(remotePeer, remoteENR, remoteNode.address)
        }
        maybeNodes <- service.findNode(caller)(remotePublicKey)
      } yield {
        maybeNodes should not be empty
        // The caller is asking for the closest nodes to itself and we know about
        // the local node and the remote node, so we should return those two.
        maybeNodes.get should have size 2
      }
    }
  }

  behavior of "enrRequest"

  it should "not respond to unbonded peers" in test {
    new Fixture {
      override val test = for {
        maybeResponse <- service.enrRequest(remotePeer)(())
      } yield {
        maybeResponse shouldBe empty
      }
    }
  }

  it should "respond with the local ENR record" in test {
    new Fixture {
      override val test = for {
        _ <- stateRef.update {
          _.withLastPongTimestamp(remotePeer, System.currentTimeMillis)
        }
        maybeResponse <- service.enrRequest(remotePeer)(())
      } yield {
        maybeResponse shouldBe Some(localENR)
      }
    }
  }

  behavior of "lookup"

  trait LookupFixture extends Fixture {
    val (targetPublicKey, _) = sigalg.newKeyPair

    def expectedTarget: Option[PublicKey] = Some(targetPublicKey)

    def newRandomNode = {
      val (publicKey, privateKey) = sigalg.newKeyPair
      val address = aRandomAddress
      val node = makeNode(publicKey, address)
      val enr = EthereumNodeRecord.fromNode(node, privateKey, seq = 1).require
      node -> enr
    }

    val randomNodes = List.fill(config.kademliaBucketSize * 2)(newRandomNode)

    override lazy val config = defaultConfig.copy(
      requestTimeout = 50.millis // To not wait for pings during bonding.
    )

    override lazy val rpc = unimplementedRPC.copy(
      ping = _ => _ => Task.pure(Some(None)),
      enrRequest = peer =>
        _ =>
          Task.pure {
            ((remoteNode -> remoteENR) +: randomNodes).find(_._1.id == peer.id).map(_._2)
          },
      findNode = _ =>
        targetPublicKey =>
          Task {
            expectedTarget.foreach(targetPublicKey shouldBe _)
          } >>
            Task.pure {
              // Every peer returns some random subset of the nodes we prepared.
              Some(Random.shuffle(randomNodes).take(config.kademliaBucketSize).map(_._1))
            }
    )

    def addRemotePeer = stateRef.update {
      _.withEnrAndAddress(remotePeer, remoteENR, remoteNode.address)
    }
  }

  it should "bond with nodes during recursive lookups before contacting them" in test {
    new LookupFixture {
      override val test = for {
        _ <- addRemotePeer
        _ <- service.lookup(targetPublicKey)
        state <- stateRef.get
      } yield {
        state.lastPongTimestampMap should contain key (remotePeer)
        assert(state.lastPongTimestampMap.size > 1)
      }
    }
  }

  it should "bond before accepting returned neighbors as new closest" in test {
    new LookupFixture {
      // Return nodes which are really close to the target but don't exist and cannot be pinged.
      val nonExistingNodes = List.fill(config.kademliaBucketSize) {
        newRandomNode._1.copy(id = targetPublicKey)
      }

      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(None),
        findNode = _ => _ => Task.pure(Some(nonExistingNodes))
      )

      override val test = for {
        // Add all the known nodes as bonded so they don't have to be pinged.
        _ <- randomNodes.traverse {
          case (node, enr) =>
            stateRef.update { state =>
              val peer = Peer(node.id, nodeAddressToInetSocketAddress(node.address))
              state.withEnrAndAddress(peer, enr, node.address).withLastPongTimestamp(peer, System.currentTimeMillis)
            }
        }
        // If they all return non-existing nodes and we eagerly consider those closest,
        // then try to ping, we won't have anything left to return.
        closest <- service.lookup(targetPublicKey)
      } yield {
        closest.size shouldBe config.kademliaBucketSize
        Inspectors.forAll(closest) { node =>
          nonExistingNodes should not contain (node)
        }
      }
    }
  }

  it should "return the k closest nodes to the target" in test {
    new LookupFixture {
      val allNodes = List(localNode -> localENR, remoteNode -> remoteENR) ++ randomNodes

      val targetId = Node.kademliaId(targetPublicKey)
      val expectedNodes = allNodes
        .map(_._1)
        .sortBy(node => Xor.d(node.kademliaId, targetId))
        .take(config.kademliaBucketSize)

      override val test = for {
        _ <- addRemotePeer
        closestNodes <- service.lookup(targetPublicKey)
        state <- stateRef.get
      } yield {
        closestNodes should contain theSameElementsInOrderAs expectedNodes
      }
    }
  }

  it should "fetch the ENR records of the nodes encountered" in test {
    new LookupFixture {
      override val test = for {
        _ <- addRemotePeer
        closestNodes <- service.lookup(targetPublicKey)
        fetching <- stateRef.get.map {
          _.fetchEnrMap.values.toList.map(_.get)
        }
        _ <- fetching.sequence
        state <- stateRef.get
      } yield {
        assert(state.enrMap.size > 2)
        Inspectors.forAtLeast(1, randomNodes.map(_._1)) { node =>
          state.enrMap should contain key (node.id)
        }
      }
    }
  }

  it should "filter out invalid relay IPs" in test {
    new Fixture {
      val localNodes = List.fill(config.kademliaBucketSize) {
        val (publicKey, privateKey) = sigalg.newKeyPair
        val address = aRandomAddress
        val node = makeNode(publicKey, address)
        val enr = EthereumNodeRecord.fromNode(node, privateKey, seq = 1).require
        node -> enr
      }

      val remoteNodes = List.range(0, config.kademliaBucketSize).map { i =>
        val (publicKey, privateKey) = sigalg.newKeyPair
        val address = new InetSocketAddress("140.82.121.4", 40000 + i)
        val node = makeNode(publicKey, address)
        val enr = EthereumNodeRecord.fromNode(node, privateKey, seq = 1).require
        node -> enr
      }

      val nodes = localNodes ++ remoteNodes

      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(Some(None)),
        enrRequest = peer => _ => Task.pure(nodes.find(_._1.id == peer.id).map(_._2)),
        // Return a mix of remote and local nodes.
        findNode =
          _ => targetPublicKey => Task.pure(Some(Random.shuffle(nodes).take(config.kademliaBucketSize).map(_._1)))
      )

      override val test = for {
        _ <- stateRef.update { state =>
          // Add the first remote peer to our database.
          val (node, enr) = remoteNodes.head
          val peer = Peer[InetSocketAddress](node.id, nodeAddressToInetSocketAddress(node.address))
          state.withEnrAndAddress(peer, enr, node.address)
        }
        // Looking up will have to use the remote peer.
        // While it gives us local addresses we should not return them from the lookup.
        closest <- service.lookup(sigalg.newKeyPair._1)
      } yield {
        closest should not be empty
        Inspectors.forAll(closest) { node =>
          localNodes.map(_._1) should not contain node
        }
      }
    }
  }

  behavior of "lookupRandom"

  it should "lookup a random node" in test {
    new LookupFixture {
      override val expectedTarget = None

      override val test = for {
        _ <- addRemotePeer
        _ <- service.lookupRandom
        state <- stateRef.get
      } yield {
        // We should bond with nodes along the way, so in the end there should
        // be more pinged nodes than just the remote we started with.
        state.lastPongTimestampMap.size should be > 1
      }
    }
  }

  behavior of "enroll"

  it should "perform a self-lookup with the bootstrap nodes" in test {
    new LookupFixture {
      override val expectedTarget = Some(localNode.id)

      override lazy val config = defaultConfig.copy(
        knownPeers = Set(remoteNode)
      )

      override val test = for {
        enrolled <- service.enroll
        state <- stateRef.get
      } yield {
        enrolled shouldBe true
        state.lastPongTimestampMap.size should be > 1
      }
    }
  }

  it should "return false if it cannot retrieve any ENRs" in test {
    new Fixture {
      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(Some(None)),
        enrRequest = _ => _ => Task.pure(None)
      )

      override lazy val config = defaultConfig.copy(
        knownPeers = Set(remoteNode)
      )

      override val test = for {
        enrolled <- service.enroll
        state <- stateRef.get
      } yield {
        enrolled shouldBe false
        state.lastPongTimestampMap should contain key (remotePeer)
      }
    }
  }

  behavior of "getNode"

  it should "return the local node" in test {
    new Fixture {
      override val test = for {
        node <- service.getNode(localPublicKey)
      } yield {
        node shouldBe Some(localNode)
      }
    }
  }

  it should "return nodes from the cache" in test {
    new Fixture {
      override val test = for {
        _ <- stateRef.update(_.withEnrAndAddress(remotePeer, remoteENR, remoteNode.address))
        node <- service.getNode(remotePublicKey)
      } yield {
        node shouldBe Some(remoteNode)
      }
    }
  }

  it should "lookup a node remotely if not found locally" in test {
    new LookupFixture {
      override val expectedTarget = Some(randomNodes.head._1.id)

      override val test = for {
        _ <- addRemotePeer
        node <- service.getNode(expectedTarget.get)
      } yield {
        node shouldBe Some(randomNodes.head._1)
      }
    }
  }

  behavior of "getNodes"

  it should "return the local node among all nodes which have an ENR" in test {
    new LookupFixture {
      override val test = for {
        nodes0 <- service.getNodes
        _ <- addRemotePeer
        nodes1 <- service.getNodes
        _ <- service.lookup(targetPublicKey)
        nodes2 <- service.getNodes
        state <- stateRef.get
      } yield {
        nodes0 shouldBe Set(localNode)
        nodes1 shouldBe Set(localNode, remoteNode)
        nodes2.size shouldBe state.enrMap.size
      }
    }
  }

  behavior of "addNode"

  it should "try to fetch the ENR of the node" in test {
    new Fixture {
      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(Some(None)),
        enrRequest = _ => _ => Task.pure(Some(remoteENR))
      )
      override val test = for {
        _ <- service.addNode(remoteNode)
        state <- stateRef.get
      } yield {
        state.lastPongTimestampMap should contain key (remotePeer)
        state.enrMap should contain key (remotePublicKey)
      }
    }
  }

  behavior of "removeNode"

  it should "remove bonded or unbonded nodes from the cache" in test {
    new Fixture {
      override val test = for {
        _ <- stateRef.update(
          _.withEnrAndAddress(remotePeer, remoteENR, remoteNode.address)
            .withLastPongTimestamp(remotePeer, System.currentTimeMillis())
        )
        _ <- service.removeNode(remotePublicKey)
        state <- stateRef.get
      } yield {
        state.enrMap should not contain key(remotePublicKey)
        state.nodeMap should not contain key(remotePublicKey)
        state.lastPongTimestampMap should not contain key(remotePeer)
        state.kBuckets.contains(remoteNode.kademliaId) shouldBe false
      }
    }
  }

  it should "not remove the local node" in test {
    new Fixture {
      override val test = for {
        _ <- service.removeNode(localPublicKey)
        state <- stateRef.get
      } yield {
        state.enrMap should contain key (localPublicKey)
        state.nodeMap should contain key (localPublicKey)
        state.kBuckets.contains(localNode.kademliaId) shouldBe true
      }
    }
  }

  behavior of "updateExternalAddress"

  it should "update the address of the local node and increment the ENR sequence" in test {
    new Fixture {
      val newIP = InetAddress.getByName("iohk.io")

      override val test = for {
        _ <- service.updateExternalAddress(newIP)
        state <- stateRef.get
      } yield {
        state.node.address.ip shouldBe newIP
        state.enr.content.seq shouldBe (localENR.content.seq + 1)
      }
    }
  }

  it should "ping existing peers with the new ENR seq" in test {
    new Fixture {
      val newIP = InetAddress.getByName("iohk.io")

      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task.pure(Some(None))
      )

      override val test = for {
        time0 <- service.currentTimeMillis
        _ <- stateRef.update {
          _.withLastPongTimestamp(remotePeer, time0)
        }
        _ <- service.updateExternalAddress(newIP)
        _ <- Task.sleep(250.millis) // It's running in the background.
        state <- stateRef.get
      } yield {
        state.lastPongTimestampMap(remotePeer) should be > time0
      }
    }
  }

  behavior of "getLocalNode"

  it should "return the latest local node record" in test {
    new Fixture {
      override val test = for {
        node <- service.getLocalNode
      } yield {
        node shouldBe localNode
      }
    }
  }
}

object DiscoveryServiceSpec {
  import DefaultCodecs._

  implicit val scheduler: Scheduler = Scheduler.Implicits.global
  implicit val sigalg = new MockSigAlg()

  /** Placeholder implementation that throws if any RPC method is called. */
  case class StubDiscoveryRPC(
      ping: DiscoveryRPC.Call[Peer[InetSocketAddress], DiscoveryRPC.Proc.Ping] = _ =>
        sys.error("Didn't expect to call ping"),
      findNode: DiscoveryRPC.Call[Peer[InetSocketAddress], DiscoveryRPC.Proc.FindNode] = _ =>
        sys.error("Didn't expect to call findNode"),
      enrRequest: DiscoveryRPC.Call[Peer[InetSocketAddress], DiscoveryRPC.Proc.ENRRequest] = _ =>
        sys.error("Didn't expect to call enrRequest")
  ) extends DiscoveryRPC[Peer[InetSocketAddress]]

  val unimplementedRPC = StubDiscoveryRPC()

  val defaultConfig = DiscoveryConfig.default.copy(
    requestTimeout = 50.millis
  )

  trait Fixture {
    def test: Task[Assertion]

    def makeNode(publicKey: PublicKey, address: InetSocketAddress) =
      Node(publicKey, Node.Address(address.getAddress, address.getPort, address.getPort))

    lazy val (localPublicKey, localPrivateKey) = sigalg.newKeyPair
    lazy val localAddress = aRandomAddress
    lazy val localNode = makeNode(localPublicKey, localAddress)
    lazy val localPeer = Peer(localPublicKey, localAddress)
    lazy val localENR = EthereumNodeRecord.fromNode(localNode, localPrivateKey, seq = 1).require

    lazy val remoteAddress = aRandomAddress
    lazy val (remotePublicKey, remotePrivateKey) = sigalg.newKeyPair
    lazy val remoteNode = makeNode(remotePublicKey, remoteAddress)
    lazy val remotePeer = Peer(remotePublicKey, remoteAddress)
    lazy val remoteENR = EthereumNodeRecord.fromNode(remoteNode, remotePrivateKey, seq = 1).require

    lazy val stateRef = Ref.unsafe[Task, DiscoveryService.State[InetSocketAddress]](
      DiscoveryService.State[InetSocketAddress](localNode, localENR)
    )

    lazy val config: DiscoveryConfig = defaultConfig.copy(
      requestTimeout = 100.millis
    )

    lazy val rpc = unimplementedRPC

    // Only using `new` for testing, normally we'd use it as a Resource with `apply`.
    lazy val service = new DiscoveryService.ServiceImpl[InetSocketAddress](
      localPrivateKey,
      config,
      rpc,
      stateRef,
      toAddress = nodeAddressToInetSocketAddress
    )
  }

  def nodeAddressToInetSocketAddress(address: Node.Address): InetSocketAddress =
    new InetSocketAddress(address.ip, address.udpPort)
}
