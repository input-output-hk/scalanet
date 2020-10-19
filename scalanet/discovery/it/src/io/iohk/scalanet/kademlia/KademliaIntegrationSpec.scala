package io.iohk.scalanet.kademlia

import java.util.concurrent.{Executors, TimeUnit}
import cats.effect.Resource
import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{Assertion, AsyncFlatSpec, BeforeAndAfterAll}
import org.scalatest.Matchers._
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scodec.bits.BitVector
import scala.language.reflectiveCalls

abstract class KademliaIntegrationSpec(name: String)
    extends AsyncFlatSpec
    with BeforeAndAfterAll
    with Eventually
    with IntegrationPatience {

  type PeerRecord <: {
    def id: BitVector
  }

  trait TestNode {
    def self: PeerRecord
    def getPeers: Task[Seq[PeerRecord]]
  }

  def makeXorOrdering(nodeId: BitVector): Ordering[PeerRecord]

  /** Generate a random peer. */
  def generatePeerRecord(): PeerRecord

  case class TestNodeKademliaConfig(
      alpha: Int = 3,
      k: Int = 20,
      serverBufferSize: Int = 2000,
      refreshRate: FiniteDuration = 15.minutes
  )

  val defaultConfig = TestNodeKademliaConfig()

  def startNode(
      selfRecord: PeerRecord = generatePeerRecord(),
      initialNodes: Set[PeerRecord] = Set(),
      testConfig: TestNodeKademliaConfig = defaultConfig
  ): Resource[Task, TestNode]

  def haveSameNumberOfPeers(nodes: Seq[TestNode], expectedNumber: Int): Task[Boolean] = {
    for {
      peersPerNode <- Task.traverse(nodes)(node => node.getPeers)
    } yield {
      peersPerNode.forall(peers => peers.size == expectedNumber)
    }
  }

  val threadPool = Executors.newFixedThreadPool(16)
  val testContext = ExecutionContext.fromExecutor(threadPool)

  implicit val scheduler = Scheduler(testContext)

  override def afterAll(): Unit = {
    threadPool.shutdown()
    threadPool.awaitTermination(60, TimeUnit.SECONDS)
    ()
  }

  def taskTestCase(t: => Task[Assertion]): Future[Assertion] = {
    t.runToFuture
  }

  behavior of s"Kademlia with $name"

  it should "only find self node when there are no bootstrap nodes" in taskTestCase {
    startNode().use { node =>
      node.getPeers.map { knownNodes =>
        knownNodes should have size 1
      }
    }
  }

  it should "enable finding nodes with common bootstrap node" in taskTestCase {
    (for {
      node <- startNode()
      node1 <- startNode(initialNodes = Set(node.self))
      node2 <- startNode(initialNodes = Set(node.self))
    } yield (node, node1, node2)).use {
      case (node, node1, node2) =>
        Task {
          eventually {
            haveSameNumberOfPeers(Seq(node, node1, node2), expectedNumber = 3).runSyncUnsafe() shouldEqual true
          }
        }
    }
  }

  it should "enable discovering neighbours of boostrap node" in taskTestCase {
    (for {
      node <- startNode()
      node1 <- startNode()
      node2 <- startNode()
      node3 <- startNode(initialNodes = Set(node.self, node1.self, node2.self))
      node4 <- startNode(initialNodes = Set(node3.self))
    } yield (node, node1, node2, node3, node4)).use {
      case (node, node1, node2, node3, node4) =>
        Task {
          eventually {
            // node3 joins 3 others, and get joined by node4
            node3.getPeers.runSyncUnsafe().size shouldEqual 5
            // node4 joins node3 so it should learn about all its peers
            node4.getPeers.runSyncUnsafe().size shouldEqual 5

            // These nodes received messages from node3 and node4 so they should add them to their routing tables,
            // but because they didn't have any initial bootstrap nodes and the default refresh cycle is much longer
            // than the test, they won't have discovered each other through node3 and node4.
            node.getPeers.runSyncUnsafe().size shouldEqual 3
            node1.getPeers.runSyncUnsafe().size shouldEqual 3
            node2.getPeers.runSyncUnsafe().size shouldEqual 3
          }
        }
    }
  }

  it should "enable discovering neighbours of the neighbours" in taskTestCase {
    (for {
      node <- startNode()
      node1 <- startNode(initialNodes = Set(node.self))
      node2 <- startNode(initialNodes = Set(node1.self))
      node3 <- startNode(initialNodes = Set(node2.self))
      node4 <- startNode(initialNodes = Set(node3.self))
    } yield (node, node1, node2, node3, node4)).use {
      case (node, node1, node2, node3, node4) =>
        Task {
          eventually {
            haveSameNumberOfPeers(Seq(node, node1, node2, node3, node4), expectedNumber = 5)
              .runSyncUnsafe() shouldEqual true
          }
        }
    }
  }

  it should "add only online nodes to routing table" in taskTestCase {
    (for {
      node <- startNode()
      node1A <- Resource.liftF(startNode().allocated)
      (node1, node1Shutdown) = node1A
      node2 <- startNode(initialNodes = Set(node.self, node1.self))
      _ <- Resource.liftF(node1Shutdown)
      node3 <- startNode(initialNodes = Set(node2.self))
    } yield (node1, node3)).use {
      case (node1, node3) =>
        Task {
          eventually {
            val peers = node3.getPeers.runSyncUnsafe()
            peers.size shouldEqual 3
            peers.contains(node1.self) shouldBe false
          }
        }
    }
  }

  it should "refresh routing table" in taskTestCase {
    val lowRefConfig = defaultConfig.copy(refreshRate = 3.seconds)
    val randomNode = generatePeerRecord()
    (for {
      node <- startNode(initialNodes = Set(randomNode), testConfig = lowRefConfig)
      node1 <- startNode(initialNodes = Set(node.self), testConfig = lowRefConfig)
      _ <- startNode(selfRecord = randomNode)
    } yield node1).use { node1 =>
      Task {
        eventually {
          node1.getPeers.runSyncUnsafe().size shouldEqual 3
        }
      }
    }
  }

  it should "refresh table with many nodes in the network " in taskTestCase {
    val lowRefConfig = defaultConfig.copy(refreshRate = 1.seconds)
    val randomNode = generatePeerRecord()
    (for {
      node1 <- startNode(initialNodes = Set(randomNode), testConfig = lowRefConfig)
      node2 <- startNode(initialNodes = Set(), testConfig = lowRefConfig)
      node3 <- startNode(initialNodes = Set(node2.self), testConfig = lowRefConfig)
      node4 <- startNode(initialNodes = Set(node3.self), testConfig = lowRefConfig)
      _ <- Resource.liftF(Task.sleep(10.seconds))
      node5 <- startNode(
        selfRecord = randomNode,
        initialNodes = Set(node2.self, node3.self, node4.self),
        testConfig = lowRefConfig
      )
    } yield (node1, node2, node3, node4, node5)).use {
      case (node1, node2, node3, node4, node5) =>
        Task {
          eventually {
            node1.getPeers.runSyncUnsafe().size shouldEqual 5
            node2.getPeers.runSyncUnsafe().size shouldEqual 5
            node3.getPeers.runSyncUnsafe().size shouldEqual 5
            node4.getPeers.runSyncUnsafe().size shouldEqual 5
            node5.getPeers.runSyncUnsafe().size shouldEqual 5
          }
        }
    }
  }

  it should "add to routing table multiple concurrent nodes" in taskTestCase {
    val nodesRound1 = List.fill(5)(generatePeerRecord())
    val nodesRound2 = List.fill(5)(generatePeerRecord())
    (for {
      node <- startNode()
      _ <- nodesRound1.map(n => startNode(n, initialNodes = Set(node.self))).sequence
      _ <- nodesRound2.map(n => startNode(n, initialNodes = Set(node.self))).sequence
    } yield node).use { node =>
      Task {
        eventually {
          node.getPeers.runSyncUnsafe().size shouldEqual 11
        }
      }
    }
  }

  it should "finish lookup when k closest nodes are found" in taskTestCase {
    // alpha = 1 makes sure we are adding nodes one by one, so the final count should be equal exactly k, if alpha > 1
    // then final count could be at least k.
    val lowKConfig = defaultConfig.copy(k = 3, alpha = 1)
    val nodes = (0 until 5).map(_ => generatePeerRecord()).toSeq
    val testNode = nodes.head
    val rest = nodes.tail.sorted(ord = makeXorOrdering(testNode.id))
    val bootStrapNode = rest.head
    val bootStrapNodeNeighbours = rest.tail.toSet

    (for {
      nodes <- bootStrapNodeNeighbours.toList.map(node => startNode(node, testConfig = lowKConfig)).sequence
      bootNode <- startNode(bootStrapNode, initialNodes = bootStrapNodeNeighbours, testConfig = lowKConfig)
      rootNode <- startNode(testNode, initialNodes = Set(bootStrapNode), testConfig = lowKConfig)
    } yield (nodes, rootNode)).use {
      case (nodes, rootNode) =>
        Task {
          nodes.size shouldEqual 3

          eventually {
            val peers = rootNode.getPeers.runSyncUnsafe()
            peers should have size 4
            peers should not contain rest.last
          }
        }
    }
  }
}
