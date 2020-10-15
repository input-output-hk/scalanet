package io.iohk.scalanet.discovery.ethereum.v4

import cats.effect.concurrent.Ref
import io.iohk.scalanet.discovery.crypto.{PublicKey, Signature}
import io.iohk.scalanet.discovery.ethereum.{EthereumNodeRecord, Node}
import io.iohk.scalanet.discovery.ethereum.codecs.DefaultCodecs
import io.iohk.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import io.iohk.scalanet.discovery.ethereum.v4.DiscoveryNetwork.Peer
import io.iohk.scalanet.NetUtils.aRandomAddress
import java.net.InetSocketAddress
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import org.scalatest._
import scala.concurrent.duration._

class DiscoveryServiceSpec extends AsyncFlatSpec with Matchers {
  import DiscoveryService.{State, BondingResults}
  import DiscoveryNetworkSpec.{randomKeyPair}
  import DiscoveryServiceSpec._

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
        decision <- service.initBond(remotePeer)
      } yield {
        decision shouldBe Right(localENR.content.seq)
      }
    }
  }
  it should "return the existing deferred result if bonding is already running" in test {
    new Fixture {
      override val test = for {
        _ <- service.initBond(remotePeer)
        decision <- service.initBond(remotePeer)
      } yield {
        decision.isLeft shouldBe true
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
    override lazy val config = defaultConfig.copy(
      requestTimeout = 100.millis // To not wait for pings during bonding.
    )
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
        _ <- service.completePing(remotePeer)
        bonded <- bonding.join
        time1 <- service.currentTimeMillis
      } yield {
        bonded shouldBe true
        assert(time1 - time0 < config.requestTimeout.toMillis)
      }
    }
  }

  it should "fetch the ENR once bonded" in test {
    new BondingFixture {
      override val test = for {
        _ <- service.bond(remotePeer)
        state <- stateRef.get
      } yield {
        state.enrMap(remotePublicKey) shouldBe remoteENR
        state.nodeMap should contain key (remotePublicKey)
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

  behavior of "fetchEnr"

  it should "only initiate one fetch at a time" in test {
    new Fixture {
      val callCount = AtomicInt(0)

      override lazy val rpc = unimplementedRPC.copy(
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
        enrRequest = _ => _ => Task(Some(remoteENR))
      )
      override val test = for {
        _ <- service.fetchEnr(remotePeer)
        state <- stateRef.get
      } yield {
        state.fetchEnrMap should not contain key(remotePeer)
        state.nodeMap should contain key (remotePeer.id)
        state.enrMap(remotePeer.id) shouldBe remoteENR
        state.kBuckets.contains(remotePeer.id) shouldBe true
      }
    }
  }

  it should "remove the node if the ENR signature validation fails" in test {
    new Fixture {
      override lazy val rpc = unimplementedRPC.copy(
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

  it should "remove the bonded status if the ENR fetch fails and there's no previous ENR" in test {
    new Fixture {
      override lazy val rpc = unimplementedRPC.copy(
        enrRequest = _ => _ => Task(None)
      )
      override val test = for {
        _ <- stateRef.update(_.withLastPongTimestamp(remotePeer, System.currentTimeMillis))
        _ <- service.fetchEnr(remotePeer)
        state <- stateRef.get
      } yield {
        state.lastPongTimestampMap should not contain key(remotePeer)
      }
    }
  }

  behavior of "ping"

  it should "bond with the caller" in test {
    new Fixture {
      override lazy val rpc = unimplementedRPC.copy(
        ping = _ => _ => Task(Some(None)),
        enrRequest = _ => _ => Task(Some(remoteENR))
      )
      override val test = for {
        _ <- service.ping(remotePeer)(None)
        _ <- stateRef.get.flatMap(_.bondingResultsMap.get(remotePeer).fold(Task.unit)(_.pongReceived.get.void))
        _ <- stateRef.get.flatMap(_.fetchEnrMap.get(remotePeer).fold(Task.unit)(_.get))
        state <- stateRef.get
      } yield {
        state.nodeMap should contain key (remotePeer.id)
        state.enrMap should contain key (remotePeer.id)
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
        val (callerPublicKey, _) = randomKeyPair
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

  behavior of "getNode"
  it should "return the local node" in (pending)
  it should "not return a nodes which is not bonded" in (pending)
  it should "return a bonded node" in (pending)
  it should "return a node from the local cache" in (pending)
  it should "lookup a node remotely if not found locally" in (pending)

  behavior of "getNodes"
  it should "not return the local node" in (pending)
  it should "not return nodes which aren't bonded" in (pending)
  it should "return bonded nodes" in (pending)

  behavior of "addNode"
  it should "try to bond with the node" in (pending)

  behavior of "removeNode"
  it should "remove bonded or unbonded nodes from the cache" in (pending)

  behavior of "updateExternalAddress"
  it should "update the address of the local node" in (pending)
  it should "increment the local ENR sequence" in (pending)

  behavior of "localNode"
  it should "return the latest local node record" in (pending)

  behavior of "enroll"
  it should "perform a self-lookup with the bootstrap nodes" in (pending)

  behavior of "startPeriodicRefresh"
  it should "periodically ping nodes" in (pending)

  behavior of "startPeriodicDiscovery"
  it should "periodically lookup a random node" in (pending)

  behavior of "startRequestHandling"
  it should "respond to pings with its local ENR sequence" in (pending)
  it should "not respond to findNode from unbonded peers" in (pending)
  it should "respond to findNode from bonded peer with the closest bonded peers" in (pending)
  it should "not respond to enrRequest from unbonded peers" in (pending)
  it should "respond to enrRequest from bonded peers with its signed local ENR" in (pending)
  it should "bond with peers that ping it" in (pending)
  it should "update the node record to the latest it connected from" in (pending)

  behavior of "lookup"
  it should "bond with nodes while doing recursive lookups before contacting them" in (pending)
  it should "return the node seeked or nothing" in (pending)
  it should "fetch the ENR record of the node" in (pending)
}

object DiscoveryServiceSpec {
  import DiscoveryNetworkSpec.{randomKeyPair}
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
  val defaultConfig = DiscoveryConfig.default

  trait Fixture {
    def test: Task[Assertion]

    def makeNode(publicKey: PublicKey, address: InetSocketAddress) =
      Node(publicKey, Node.Address(address.getAddress, address.getPort, address.getPort))

    lazy val (localPublicKey, localPrivateKey) = randomKeyPair
    lazy val localAddress = aRandomAddress()
    lazy val localNode = makeNode(localPublicKey, localAddress)
    lazy val localPeer = Peer(localPublicKey, localAddress)
    lazy val localENR = EthereumNodeRecord.fromNode(localNode, localPrivateKey, seq = 1).require

    lazy val remoteAddress = aRandomAddress()
    lazy val (remotePublicKey, remotePrivateKey) = randomKeyPair
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

    lazy val service = new DiscoveryService.ServiceImpl[InetSocketAddress](rpc, stateRef, config)
  }
}
