package io.iohk.scalanet.discovery.ethereum.v4

import cats.effect.{Clock, Resource, Fiber}
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import io.iohk.scalanet.discovery.crypto.{PublicKey}
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import io.iohk.scalanet.kademlia.KBuckets
import java.net.InetAddress
import monix.catnap.CancelableF
import monix.eval.Task
import scala.concurrent.duration._
import scala.util.control.NonFatal

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

  type ENRSeq = Long
  type Timestamp = Long
  type NodeId = PublicKey
  type StateRef[A] = Ref[Task, State[A]]

  type Peer[A] = (NodeId, A)
  implicit class PeerOps[A](peer: Peer[A]) {
    def id: NodeId = peer._1
    def address: A = peer._2
  }

  type PingingResult = Deferred[Task, Boolean]

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
      network: DiscoveryNetwork[A],
      bondExpiration: FiniteDuration = 12.hours,
      requestTimeout: FiniteDuration = 3.seconds
  ): Resource[Task, DiscoveryService] =
    Resource
      .make {
        for {
          stateRef <- Ref[Task].of(State[A](node, enr))
          service <- Task(new DiscoveryServiceImpl[A](network, stateRef, bondExpiration, requestTimeout))
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
      // Last time a peer responded with a Pong to our Ping.
      lastPongTimestampMap: Map[Peer[A], Timestamp],
      // Deferred results so we can ensure there's only one concurrent Ping to a given peer.
      pingingResultMap: Map[Peer[A], PingingResult]
  ) {
    def withLastPongTimestamp(peer: Peer[A], timestamp: Timestamp): State[A] =
      copy(lastPongTimestampMap = lastPongTimestampMap.updated(peer, timestamp))

    def withPingingResult(peer: Peer[A], result: PingingResult): State[A] =
      copy(pingingResultMap = pingingResultMap.updated(peer, result))

    def clearPingingResult(peer: Peer[A]): State[A] =
      copy(pingingResultMap = pingingResultMap - peer)
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
      lastPongTimestampMap = Map.empty[Peer[A], Timestamp],
      pingingResultMap = Map.empty[Peer[A], PingingResult]
    )
  }

  class DiscoveryServiceImpl[A](
      network: DiscoveryNetwork[A],
      stateRef: StateRef[A],
      bondExpiration: FiniteDuration,
      requestTimeout: FiniteDuration
  )(implicit clock: Clock[Task])
      extends DiscoveryService
      with DiscoveryRPC[Peer[A]] {

    implicit val sr = stateRef

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

    def bond(peer: Peer[A]): Task[Boolean] =
      DiscoveryService.bond(network, bondExpiration, requestTimeout, peer)

    def lookup(nodeId: NodeId): Task[Option[Node]] = ???

  }

  private def currentTimeMillis(implicit clock: Clock[Task]): Task[Long] =
    clock.realTime(MILLISECONDS)

  /** Check if the given peer has a valid bond at the moment. */
  protected[v4] def isBonded[A](
      bondExpiration: FiniteDuration,
      peer: Peer[A]
  )(implicit sr: StateRef[A], clock: Clock[Task]): Task[Boolean] = {
    currentTimeMillis.flatMap { now =>
      sr.get.map { state =>
        if (peer.id == state.node.id)
          true
        else
          state.lastPongTimestampMap.get(peer) match {
            case None =>
              false
            case Some(timestamp) =>
              timestamp > now - bondExpiration.toMillis
          }
      }
    }
  }

  /** Runs the bonding process with the peer, unless already bonded.
    *
    * If the process is already running it waits for the result of that,
    * it doesn't send another ping.
    *
    * If the peer responds it waits for a potential ping to arrive from them,
    * so we can have some reassurance that the peer is also bonded with us
    * and will not ignore our messages.
    */
  protected[v4] def bond[A](
      rpc: DiscoveryRPC[A],
      bondExpiration: FiniteDuration,
      // How long to wait for the remote peer to send a ping to us.
      requestTimeout: FiniteDuration,
      peer: Peer[A]
  )(implicit sr: StateRef[A], clock: Clock[Task]): Task[Boolean] = {
    isBonded(bondExpiration, peer).flatMap {
      case true =>
        Task.pure(true)

      case false =>
        initBond(peer).flatMap {
          case Left(result) =>
            result.get

          case Right(enrSeq) =>
            rpc
              .ping(peer.address)(Some(enrSeq))
              .recover {
                case NonFatal(_) => None
              }
              .flatMap {
                case Some(maybeRemoteEnrSeq) =>
                  // TODO: Wait for a ping.
                  // TODO: Schedule fetching the ENR.
                  completeBond(peer, responded = true).as(true)
                case None =>
                  completeBond(peer, responded = false).as(false)
              }
        }
    }
  }

  /** Check and modify the bonding state of the peer: if we're already bonding
    * return the Deferred result we can wait on, otherwise add a new Deferred
    * and return the current local ENR sequence we can ping with.
    */
  protected[v4] def initBond[A](peer: Peer[A])(implicit sr: StateRef[A]): Task[Either[PingingResult, ENRSeq]] =
    Deferred[Task, Boolean].flatMap { d =>
      sr.modify { state =>
        state.pingingResultMap.get(peer) match {
          case Some(result) =>
            state -> Left(result)

          case _ =>
            state.withPingingResult(peer, d) -> Right(state.enr.content.seq)
        }
      }
    }

  /** Update the bonding state of the peer with the result,
    * notifying all potentially waiting bonding processes about the outcome as well.
    */
  protected[v4] def completeBond[A](peer: Peer[A], responded: Boolean)(
      implicit sr: StateRef[A],
      clock: Clock[Task]
  ): Task[Unit] = {
    currentTimeMillis.flatMap { now =>
      sr.modify { state =>
          val maybeResult = state.pingingResultMap.get(peer) match {
            case Some(result) => Some(result)
            case _ => None
          }

          val newState = if (responded) state.withLastPongTimestamp(peer, now) else state

          newState.clearPingingResult(peer) -> maybeResult
        }
        .flatMap {
          case Some(result) => result.complete(responded)
          case None => Task.unit
        }
    }
  }
}
