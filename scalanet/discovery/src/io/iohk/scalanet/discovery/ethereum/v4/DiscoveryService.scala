package io.iohk.scalanet.discovery.ethereum.v4

import cats.effect.{Resource, Fiber}
import cats.effect.concurrent.{Deferred, Ref}
import io.iohk.scalanet.discovery.crypto.{PublicKey}
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import io.iohk.scalanet.kademlia.KBuckets
import monix.eval.Task
import monix.catnap.CancelableF
import java.net.InetAddress
import scala.concurrent.duration._
import cats.effect.Clock

/** Represent the minimal set of operations the rest of the system
  * can expect from the service to be able to talk to other peers.
  */
trait DiscoveryService {
  import DiscoveryService.NodeId

  /** Try to look up a node either in the local cache or
    * by performing a recursive lookup on the network. */
  def getNode(nodeId: NodeId): Task[Option[Node]]

  /** Return all currently bonded nodes. */
  def getNodes: Task[Set[Node]]

  /** Add a node to the local cache and try to bond with it. */
  def addNode(node: Node): Task[Unit]

  /** Remove a node from the local cache. */
  def removeNode(nodeId: NodeId): Task[Unit]

  /** Update the local node with an updated external address,
    * incrementing the local ENR sequence.
    */
  def updateExternalAddress(address: InetAddress): Task[Unit]

  /** The local node representation. */
  def localNode: Task[Node]
}

object DiscoveryService {
  import DiscoveryRPC.{Call, Proc}

  type NodeId = PublicKey
  type StateRef[A] = Ref[Task, State[A]]

  type Peer[A] = (NodeId, A)
  implicit class PeerOps[A](peer: Peer[A]) {
    def id: NodeId = peer._1
    def address: A = peer._2
  }

  /** Implement the Discovery v4 protocol:
    *
    * https://github.com/ethereum/devp2p/blob/master/discv4.md
    *
    * - maintain the state of K-buckets
    * - return node candidates for the rest of the system
    * - bond with the other nodes
    * - respond to incoming requests
    * - periodically try to discover new nodes
    * - periodically ping nodes
    */
  def apply[A](
      node: Node,
      enr: EthereumNodeRecord,
      network: DiscoveryNetwork[A]
  ): Resource[Task, DiscoveryService] =
    Resource
      .make {
        for {
          stateRef <- Ref[Task].of(State[A](node, enr))
          service <- Task(new DiscoveryServiceImpl[A](network, stateRef))
          cancelToken <- service.startRequestHandling()
          _ <- service.enroll()
          refreshFiber <- service.startPeriodicRefresh()
          discoveryFiber <- service.startPeriodicDiscovery()
        } yield (service, cancelToken, refreshFiber, discoveryFiber)
      } {
        case (_, cancelToken, refreshFiber, discoveryFiber) =>
          cancelToken.cancel >> refreshFiber.cancel >> discoveryFiber.cancel
      }
      .map(_._1)

  case class State[A](
      node: Node,
      enr: EthereumNodeRecord,
      // Kademlia buckets with node IDs in them.
      kBuckets: KBuckets,
      nodeMap: Map[NodeId, Node],
      enrMap: Map[NodeId, EthereumNodeRecord],
      bondingStateMap: Map[Peer[A], BondingState]
  ) {
    def withBondingState(caller: Peer[A], bondingState: BondingState) =
      copy(bondingStateMap = bondingStateMap.updated(caller, bondingState))
  }
  object State {
    def apply[A](
        node: Node,
        enr: EthereumNodeRecord,
        clock: java.time.Clock = java.time.Clock.systemUTC()
    ): State[A] = State[A](
      node = node,
      enr = enr,
      kBuckets = new KBuckets(node.id, clock),
      nodeMap = Map(node.id -> node),
      enrMap = Map(node.id -> enr),
      bondingStateMap = Map.empty[Peer[A], BondingState]
    )
  }

  sealed trait BondingState
  object BondingState {

    /** Bonding has already been initiated, the Deferred will be completed with the
      * the eventual result which is `true` if the peer responded or `false` if it didn't. */
    case class Pinging(result: Deferred[Task, Boolean]) extends BondingState

    /** Responded to a Ping with a Pong at the given UNIX timestamp. */
    case class Responded(timestamp: Long) extends BondingState
  }

  class DiscoveryServiceImpl[A](
      network: DiscoveryNetwork[A],
      stateRef: StateRef[A]
  ) extends DiscoveryService
      with DiscoveryRPC[Peer[A]] {

    override def getNode(nodeId: NodeId): Task[Option[Node]] = ???
    override def getNodes: Task[Set[Node]] = ???
    override def addNode(node: Node): Task[Unit] = ???
    override def removeNode(nodeId: NodeId): Task[Unit] = ???
    override def updateExternalAddress(address: InetAddress): Task[Unit] = ???
    override def localNode: Task[Node] = ???
    override def ping: Call[Peer[A], Proc.Ping] = ???
    override def findNode: Call[Peer[A], Proc.FindNode] = ???
    override def enrRequest: Call[Peer[A], Proc.ENRRequest] = ???

    def enroll(): Task[Unit] = ???

    def startRequestHandling(): Task[CancelableF[Task]] =
      network.startHandling(this)

    def startPeriodicRefresh(): Task[Fiber[Task, Unit]] = ???
    def startPeriodicDiscovery(): Task[Fiber[Task, Unit]] = ???

    def bond(): Task[Boolean] = ???
    def lookup(nodeId: NodeId): Task[Option[Node]] = ???
  }

  /** Check if the given peer has a valid bond at the moment. */
  def isBonded[A](
      expiration: FiniteDuration
  )(peer: Peer[A])(implicit sr: StateRef[A], clock: Clock[Task]): Task[Boolean] = {
    clock.realTime(MILLISECONDS).flatMap { now =>
      sr.get.map { state =>
        if (peer.id == state.node.id)
          true
        else
          state.bondingStateMap.get(peer) match {
            case None =>
              false
            case Some(BondingState.Pinging(_)) =>
              false
            case Some(BondingState.Responded(timestamp)) =>
              timestamp > now - expiration.toMillis
          }
      }
    }
  }
}
