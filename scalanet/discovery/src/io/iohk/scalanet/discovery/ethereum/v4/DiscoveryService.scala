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
  import DiscoveryNetwork.Peer

  type ENRSeq = Long
  type Timestamp = Long
  type NodeId = PublicKey
  type StateRef[A] = Ref[Task, State[A]]

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
      config: DiscoveryConfig
  ): Resource[Task, DiscoveryService] =
    Resource
      .make {
        for {
          stateRef <- Ref[Task].of(State[A](node, enr))
          service <- Task(new DiscoveryServiceImpl[A](network, stateRef, config))
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

  protected[v4] case class BondingResults(
      // Completed if the remote poor responds with a Pong during the bonding process.
      pongReceived: Deferred[Task, Boolean],
      // Completed if the remote peer pings us during the bonding process.
      pingReceived: Deferred[Task, Unit]
  )
  protected[v4] object BondingResults {
    def apply(): Task[BondingResults] =
      for {
        pong <- Deferred[Task, Boolean]
        ping <- Deferred[Task, Unit]
      } yield BondingResults(pong, ping)

    def unsafe(): BondingResults =
      BondingResults(Deferred.unsafe[Task, Boolean], Deferred.unsafe[Task, Unit])
  }

  protected[v4] case class State[A](
      node: Node,
      enr: EthereumNodeRecord,
      // Kademlia buckets with node IDs in them.
      kBuckets: KBuckets,
      nodeMap: Map[NodeId, Node],
      enrMap: Map[NodeId, EthereumNodeRecord],
      // Last time a peer responded with a Pong to our Ping.
      lastPongTimestampMap: Map[Peer[A], Timestamp],
      // Deferred results so we can ensure there's only one concurrent Ping to a given peer.
      bondingResultsMap: Map[Peer[A], BondingResults]
  ) {
    def withLastPongTimestamp(peer: Peer[A], timestamp: Timestamp): State[A] =
      copy(lastPongTimestampMap = lastPongTimestampMap.updated(peer, timestamp))

    def withBondingResults(peer: Peer[A], results: BondingResults): State[A] =
      copy(bondingResultsMap = bondingResultsMap.updated(peer, results))

    def clearBondingResults(peer: Peer[A]): State[A] =
      copy(bondingResultsMap = bondingResultsMap - peer)
  }
  protected[v4] object State {
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
      bondingResultsMap = Map.empty[Peer[A], BondingResults]
    )
  }

  private class DiscoveryServiceImpl[A](
      network: DiscoveryNetwork[A],
      stateRef: StateRef[A],
      config: DiscoveryConfig
  )(implicit clock: Clock[Task])
      extends DiscoveryService
      with DiscoveryRPC[Peer[A]] {

    // Passing the state to the pure functions that live on the
    // companion object, to make them easily testable.
    implicit val sr = stateRef

    override def getNode(nodeId: NodeId): Task[Option[Node]] = ???
    override def getNodes: Task[Set[Node]] = ???
    override def addNode(node: Node): Task[Unit] = ???
    override def removeNode(nodeId: NodeId): Task[Unit] = ???
    override def updateExternalAddress(address: InetAddress): Task[Unit] = ???
    override def localNode: Task[Node] = ???

    override def ping =
      caller =>
        maybeRemoteEnrSeq =>
          for {
            _ <- DiscoveryService.completePing(caller)
            enr <- stateRef.get.map(_.enr.content.seq)
            // TODO: Check if the ENR is fresher than what we have and maybe fetch again.
          } yield Some(Some(enr))

    override def findNode: Call[Peer[A], Proc.FindNode] = ???
    override def enrRequest: Call[Peer[A], Proc.ENRRequest] = ???

    def enroll(): Task[Unit] = ???

    def startRequestHandling(): Task[CancelableF[Task]] =
      network.startHandling(this)

    def startPeriodicRefresh(): Task[Fiber[Task, Unit]] = ???
    def startPeriodicDiscovery(): Task[Fiber[Task, Unit]] = ???

    def bond(peer: Peer[A]): Task[Boolean] =
      DiscoveryService.bond(peer, network, config.bondExpiration, config.requestTimeout)

    def lookup(nodeId: NodeId): Task[Option[Node]] = ???

  }

  protected[v4] def currentTimeMillis(implicit clock: Clock[Task]): Task[Long] =
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
      peer: Peer[A],
      rpc: DiscoveryRPC[Peer[A]],
      bondExpiration: FiniteDuration,
      // How long to wait for the remote peer to send a ping to us.
      requestTimeout: FiniteDuration
  )(implicit sr: StateRef[A], clock: Clock[Task]): Task[Boolean] =
    isBonded(bondExpiration, peer).flatMap {
      case true =>
        Task.pure(true)

      case false =>
        initBond(peer).flatMap {
          case Left(result) =>
            result.pongReceived.get

          case Right(enrSeq) =>
            rpc
              .ping(peer)(Some(enrSeq))
              .recover {
                case NonFatal(_) => None
              }
              .flatMap {
                case Some(maybeRemoteEnrSeq) =>
                  awaitPing(peer, requestTimeout) >> completeBond(peer, responded = true)
                // TODO: Schedule fetching the ENR.
                case None =>
                  completeBond(peer, responded = false)
              }
        }
    }

  /** Check and modify the bonding state of the peer: if we're already bonding
    * return the Deferred result we can wait on, otherwise add a new Deferred
    * and return the current local ENR sequence we can ping with.
    */
  protected[v4] def initBond[A](peer: Peer[A])(implicit sr: StateRef[A]): Task[Either[BondingResults, ENRSeq]] =
    for {
      results <- BondingResults()
      decision <- sr.modify { state =>
        state.bondingResultsMap.get(peer) match {
          case Some(results) =>
            state -> Left(results)

          case None =>
            state.withBondingResults(peer, results) -> Right(state.enr.content.seq)
        }
      }
    } yield decision

  /** Update the bonding state of the peer with the result,
    * notifying all potentially waiting bonding processes about the outcome as well.
    */
  protected[v4] def completeBond[A](peer: Peer[A], responded: Boolean)(
      implicit sr: StateRef[A],
      clock: Clock[Task]
  ): Task[Boolean] = {
    for {
      now <- currentTimeMillis
      _ <- sr
        .modify { state =>
          val maybePong = state.bondingResultsMap.get(peer).map(_.pongReceived)
          val newState = if (responded) state.withLastPongTimestamp(peer, now) else state
          newState.clearBondingResults(peer) -> maybePong
        }
        .flatMap { maybePongReceived =>
          maybePongReceived.fold(Task.unit)(_.complete(responded))
        }
    } yield responded
  }

  /** Allow the remote peer to ping us during bonding, so that we can have a more
    * fungible expectation that if we send a message they will consider us bonded and
    * not ignore it.
    *
    * The deferred should be completed by the ping handler.
    */
  protected[v4] def awaitPing[A](peer: Peer[A], requestTimeout: FiniteDuration)(
      implicit sr: StateRef[A]
  ): Task[Unit] =
    sr.get
      .map { state =>
        state.bondingResultsMap.get(peer).map(_.pingReceived)
      }
      .flatMap { maybePingReceived =>
        maybePingReceived.fold(Task.unit)(_.get.timeoutTo(requestTimeout, Task.unit))
      }

  /** Complete any deferred we set up during a bonding process expecting a ping to arrive. */
  protected[v4] def completePing[A](peer: Peer[A])(implicit sr: StateRef[A]): Task[Unit] =
    sr.get
      .map { state =>
        state.bondingResultsMap.get(peer).map(_.pingReceived)
      }
      .flatMap { maybePingReceived =>
        maybePingReceived.fold(Task.unit)(_.complete(()).attempt.void)
      }
}
