
package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.KBuckets

import java.security.SecureRandom
import java.util.concurrent.{Executors, TimeUnit}

import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.{InetMultiAddress, InetPeerGroupUtils, UDPPeerGroup}
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{Assertion, AsyncFlatSpec, BeforeAndAfterAll}
import org.scalatest.Matchers._
import KademliaIntegrationSpec._
import org.scalatest.concurrent.{Eventually, IntegrationPatience}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class KademliaIntegrationSpec extends AsyncFlatSpec with BeforeAndAfterAll with Eventually with IntegrationPatience {
  val threadPool = Executors.newFixedThreadPool(16)
  val testContext = ExecutionContext.fromExecutor(threadPool)
  implicit val scheduler = Scheduler(testContext)

  override def afterAll(): Unit = {
    threadPool.shutdown()
    threadPool.awaitTermination(60, TimeUnit.SECONDS)
  }

  "Kademlia" should "only find self node when there are no bootstrap nodes" in taskTestCase {
    for {
      node <- startNode()
      knownNodes <- node.router.nodeRecords
    } yield {
      knownNodes.values.size shouldEqual 1
    }
  }

  it should "enable finding nodes with common bootstrap node" in taskTestCase {
    for {
      node <- startNode()
      node1 <- startNode(initialNodes = Set(node.self))
      node2 <- startNode(initialNodes = Set(node.self))
    } yield {
      eventually {
        haveSameNumberOfPeers(Seq(node, node1, node2), expectedNumber = 3).runSyncUnsafe() shouldEqual true
      }
    }
  }

  it should "enable discovering neighbours of boostrap node" in taskTestCase {
    for {
      node <- startNode()
      node1 <- startNode()
      node2 <- startNode()
      node3 <- startNode(initialNodes = Set(node.self, node1.self, node2.self))
      node4 <- startNode(initialNodes = Set(node3.self))
    } yield {
      eventually {
        node4.getPeers.runSyncUnsafe().size shouldEqual 5
        node3.getPeers.runSyncUnsafe().size shouldEqual 5

        // this nodes received messages from node3 and node4 so they should add them to their routing tables
        node.getPeers.runSyncUnsafe().size shouldEqual 3
        node1.getPeers.runSyncUnsafe().size shouldEqual 3
        node2.getPeers.runSyncUnsafe().size shouldEqual 3
      }
    }
  }

  it should "enable discovering neighbours of the neighbours" in taskTestCase {
    for {
      node <- startNode()
      node1 <- startNode(initialNodes = Set(node.self))
      node2 <- startNode(initialNodes = Set(node1.self))
      node3 <- startNode(initialNodes = Set(node2.self))
      node4 <- startNode(initialNodes = Set(node3.self))
    } yield {
      eventually {
        haveSameNumberOfPeers(Seq(node, node1, node2, node3, node4), expectedNumber = 5)
          .runSyncUnsafe() shouldEqual true
      }
    }
  }

  it should "add only online nodes to routing table" in taskTestCase {
    for {
      node <- startNode()
      node1 <- startNode()
      node2 <- startNode(initialNodes = Set(node.self, node1.self))
      _ <- node1.shutdown()
      node3 <- startNode(initialNodes = Set(node2.self))
    } yield {
      eventually {
        val peers = node3.getPeers.runSyncUnsafe()
        peers.size shouldEqual 3
        peers.contains(node1.self) shouldBe false
      }
    }
  }

  it should "refresh routing table" in taskTestCase {
    val lowRefConfig = defaultConfig.copy(refreshRate = 3.seconds)
    val randomNode = generateNodeRecord()
    for {
      node <- startNode(initialNodes = Set(randomNode), testConfig = lowRefConfig)
      node1 <- startNode(initialNodes = Set(node.self), testConfig = lowRefConfig)
      rNode <- startNode(selfRecord = randomNode)
    } yield {
      eventually {
        node1.getPeers.runSyncUnsafe().size shouldEqual 3
      }
    }
  }

  it should "add to routing table multiple concurrent nodes" in taskTestCase {
    val nodesRound1 = (0 until 5).map(_ => generateNodeRecord()).toSeq
    val nodesRound2 = (0 until 5).map(_ => generateNodeRecord()).toSeq

    println(nodesRound1)
    println(nodesRound2)
    for {
      node <- startNode()
      node1 <- Task.wanderUnordered(nodesRound1)(n => startNode(n, initialNodes = Set(node.self)))
      node2 <- Task.wanderUnordered(nodesRound2)(n => startNode(n, initialNodes = Set(node.self)))
    } yield {
      eventually {
        node.getPeers.runSyncUnsafe().size shouldEqual 11
      }
    }
  }

  it should "finish lookup when k closest nodes are found" in taskTestCase {
    // alpha = 1 makes sure we are adding nodes one by one, so the final count should be equal exactly k, if alpha > 1
    // then final count could be at least k.
    val lowKConfig = defaultConfig.copy(k = 3, alpha = 1)
    val nodes = (0 until 5).map(_ => generateNodeRecord()).toSeq
    val testNode = nodes.head
    val rest = nodes.tail.sorted(ord = new XorNodeOrdering[InetMultiAddress](testNode.id))
    val bootStrapNode = rest.head
    val bootStrapNodeNeighbours = rest.tail.toSet

    for {
      nodes <- Task.traverse(bootStrapNodeNeighbours)(node => startNode(node, testConfig = lowKConfig))
      bootNode <- startNode(bootStrapNode, initialNodes = bootStrapNodeNeighbours, testConfig = lowKConfig)
      rootNode <- startNode(testNode, initialNodes = Set(bootStrapNode), testConfig = lowKConfig)
    } yield {
      nodes.size shouldEqual 3

      eventually {
        val peers = rootNode.getPeers.runSyncUnsafe()
        peers.contains(rest.last) shouldBe false
        peers.size shouldEqual 4
      }
    }
  }
}

object KademliaIntegrationSpec {
  def taskTestCase(t: => Task[Assertion])(implicit s: Scheduler): Future[Assertion] = {
    t.runToFuture
  }

  val randomGen = new SecureRandom()
  val testBitLength = 16
  import io.iohk.scalanet.peergroup.kademlia.BitVectorCodec._
  import io.iohk.decco.auto._
  import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer

  def startRouter(
                   udpConfig: UDPPeerGroup.Config,
                   routerConfig: KRouter.Config[InetMultiAddress]
                 )(implicit s: Scheduler): Task[(KRouter[InetMultiAddress], UDPPeerGroup[KMessage[InetMultiAddress]])] = {

    for {
      udpPeerGroup <- Task(new UDPPeerGroup[KMessage[InetMultiAddress]](udpConfig))
      _ <- udpPeerGroup.initialize()
      kademliaNetwork = new KNetworkScalanetImpl(udpPeerGroup)
      router <- KRouter.startRouterWithServerPar(routerConfig, kademliaNetwork)
    } yield (router, udpPeerGroup)
  }

  case class TestNode(
                       self: NodeRecord[InetMultiAddress],
                       router: KRouter[InetMultiAddress],
                       underLyingGroup: UDPPeerGroup[KMessage[InetMultiAddress]]
                     ) {
    def getPeers: Task[Seq[NodeRecord[InetMultiAddress]]] = {
      router.nodeRecords.map(_.values.toSeq)
    }

    def shutdown(): Task[Unit] = {
      underLyingGroup.shutdown()
    }

  }

  case class TestNodeKademliaConfig(
                                     alpha: Int = 3,
                                     k: Int = 20,
                                     serverBufferSize: Int = 2000,
                                     refreshRate: FiniteDuration = 15.minutes
                                   )

  val defaultConfig = TestNodeKademliaConfig()

  def generateNodeRecord(): NodeRecord[InetMultiAddress] = {
    val address = InetMultiAddress(InetPeerGroupUtils.aRandomAddress())
    val id = KBuckets.generateRandomId(testBitLength, randomGen)
    NodeRecord(id, address, address)
  }

  //val builder = (selfRecord, initialNodes) => KRouter.Config(selfRecord, initialNodes)

  def startNode(
                 selfRecord: NodeRecord[InetMultiAddress] = generateNodeRecord(),
                 initialNodes: Set[NodeRecord[InetMultiAddress]] = Set(),
                 testConfig: TestNodeKademliaConfig = defaultConfig
               )(implicit s: Scheduler): Task[TestNode] = {
    val udpConfig = UDPPeerGroup.Config(selfRecord.routingAddress.inetSocketAddress)
    val routerConfig = KRouter.Config(
      selfRecord,
      initialNodes,
      alpha = testConfig.alpha,
      k = testConfig.k,
      serverBufferSize = testConfig.serverBufferSize,
      refreshRate = testConfig.refreshRate
    )

    for {
      init <- startRouter(udpConfig, routerConfig)
      (router, underlyingGroup) = init
    } yield TestNode(selfRecord, router, underlyingGroup)
  }

  def haveSameNumberOfPeers(nodes: Seq[TestNode], expectedNumber: Int): Task[Boolean] = {
    (Task.traverse(nodes)(node => node.getPeers)).map { peers =>
      peers.forall(nodePeers => nodePeers.size == expectedNumber)
    }
  }
}