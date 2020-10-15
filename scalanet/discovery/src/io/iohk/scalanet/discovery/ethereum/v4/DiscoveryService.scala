package io.iohk.scalanet.discovery.ethereum.v4

import cats.effect.{Clock, Resource, Fiber}
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import io.iohk.scalanet.discovery.crypto.{PublicKey, SigAlg}
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import io.iohk.scalanet.kademlia.KBuckets
import java.net.InetAddress
import monix.eval.Task
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scodec.{Codec, Attempt}
import com.typesafe.scalalogging.LazyLogging

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
      config: DiscoveryConfig,
      toAddress: Node.Address => A
  )(implicit sigalg: SigAlg, enrCodec: Codec[EthereumNodeRecord.Content]): Resource[Task, DiscoveryService] =
    Resource
      .make {
        for {
          stateRef <- Ref[Task].of(State[A](node, enr))
          service <- Task(new ServiceImpl[A](network, stateRef, config, toAddress))
          // Start handling requests, we need them to enroll.
          cancelToken <- network.startHandling(service)
          // Contact the bootstrap nodes.
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
      bondingResultsMap: Map[Peer[A], BondingResults],
      // Deferred ENR fetches so we only do one at a time to a given peer.
      fetchEnrMap: Map[Peer[A], Deferred[Task, Unit]]
  ) {
    def withLastPongTimestamp(peer: Peer[A], timestamp: Timestamp): State[A] =
      copy(lastPongTimestampMap = lastPongTimestampMap.updated(peer, timestamp))

    def withBondingResults(peer: Peer[A], results: BondingResults): State[A] =
      copy(bondingResultsMap = bondingResultsMap.updated(peer, results))

    def withEnrAndAddress(peer: Peer[A], enr: EthereumNodeRecord, address: Node.Address): State[A] =
      copy(
        enrMap = enrMap.updated(peer.id, enr),
        nodeMap = nodeMap.updated(peer.id, Node(peer.id, address)),
        kBuckets = kBuckets.add(peer.id)
      )

    def clearBondingResults(peer: Peer[A]): State[A] =
      copy(bondingResultsMap = bondingResultsMap - peer)

    def withEnrFetch(peer: Peer[A], result: Deferred[Task, Unit]): State[A] =
      copy(fetchEnrMap = fetchEnrMap.updated(peer, result))

    def clearEnrFetch(peer: Peer[A]): State[A] =
      copy(fetchEnrMap = fetchEnrMap - peer)

    def removePeer(peer: Peer[A]): State[A] =
      copy(
        nodeMap = nodeMap - peer.id,
        enrMap = enrMap - peer.id,
        lastPongTimestampMap = lastPongTimestampMap - peer,
        bondingResultsMap = bondingResultsMap - peer,
        kBuckets = kBuckets.remove(peer.id)
      )
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
      bondingResultsMap = Map.empty[Peer[A], BondingResults],
      fetchEnrMap = Map.empty[Peer[A], Deferred[Task, Unit]]
    )
  }

  protected[v4] class ServiceImpl[A](
      rpc: DiscoveryRPC[Peer[A]],
      stateRef: StateRef[A],
      config: DiscoveryConfig,
      toAddress: Node.Address => A
  )(implicit clock: Clock[Task], sigalg: SigAlg, enrCodec: Codec[EthereumNodeRecord.Content])
      extends DiscoveryService
      with DiscoveryRPC[Peer[A]]
      with LazyLogging {

    override def getNode(nodeId: NodeId): Task[Option[Node]] = ???
    override def getNodes: Task[Set[Node]] = ???
    override def addNode(node: Node): Task[Unit] = ???
    override def removeNode(nodeId: NodeId): Task[Unit] = ???
    override def updateExternalAddress(address: InetAddress): Task[Unit] = ???
    override def localNode: Task[Node] = ???

    override def ping: Call[Peer[A], Proc.Ping] =
      caller =>
        maybeRemoteEnrSeq =>
          for {
            // Complete pings, if we initiated the bonding.
            _ <- completePing(caller)
            // Try to bond back if this is a new node.
            _ <- bond(caller).startAndForget
            // We may already be bonded but the remote node could have changed its address.
            _ <- maybeFetchEnr(caller, maybeRemoteEnrSeq).startAndForget
            enrSeq <- stateRef.get.map(_.enr.content.seq)
          } yield Some(Some(enrSeq))

    override def findNode: Call[Peer[A], Proc.FindNode] =
      caller =>
        target =>
          ifBonded(caller) {
            for {
              state <- stateRef.get
              closestNodeIds = state.kBuckets.closestNodes(target, config.kademliaBucketSize)
              closestNodes = closestNodeIds.map(id => state.nodeMap.get(PublicKey(id))).flatten
            } yield closestNodes
          }

    override def enrRequest: Call[Peer[A], Proc.ENRRequest] =
      caller => _ => ifBonded(caller)(stateRef.get.map(_.enr))

    def enroll(): Task[Unit] = ???

    def startPeriodicRefresh(): Task[Fiber[Task, Unit]] = ???
    def startPeriodicDiscovery(): Task[Fiber[Task, Unit]] = ???

    def lookup(nodeId: NodeId): Task[Option[Node]] = ???

    // The methods below are `protected[v4]` so that they can be called from tests individually.
    // Initially they were in the companion object as pure functions but there are just too many
    // parameters to pass around.

    protected[v4] def currentTimeMillis: Task[Long] =
      clock.realTime(MILLISECONDS)

    /** Check if the given peer has a valid bond at the moment. */
    protected[v4] def isBonded(
        peer: Peer[A]
    ): Task[Boolean] = {
      currentTimeMillis.flatMap { now =>
        stateRef.get.map { state =>
          if (peer.id == state.node.id)
            true
          else
            state.lastPongTimestampMap.get(peer) match {
              case None =>
                false
              case Some(timestamp) =>
                timestamp > now - config.bondExpiration.toMillis
            }
        }
      }
    }

    protected[v4] def ifBonded[T](caller: Peer[A])(thunk: Task[T]): Task[Option[T]] =
      isBonded(caller).ifM(thunk.map(Some(_)), Task.pure(None))

    /** Runs the bonding process with the peer, unless already bonded.
      *
      * If the process is already running it waits for the result of that,
      * it doesn't send another ping.
      *
      * If the peer responds it waits for a potential ping to arrive from them,
      * so we can have some reassurance that the peer is also bonded with us
      * and will not ignore our messages.
      */
    protected[v4] def bond(
        peer: Peer[A]
    ): Task[Boolean] =
      isBonded(peer).flatMap {
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
                    for {
                      // Update the time in case there are incoming requests from that node.
                      _ <- updateLastPongTime(peer)
                      // Allow some time for the reciprocating ping to arrive.
                      _ <- awaitPing(peer)
                      // Complete all bonds waiting on this pong, after any pings were received
                      // so that we can now try and send requests with as much confidence as we can get.
                      _ <- completePong(peer, responded = true)
                      // TODO: Update the k-bucket timestamp.
                      _ <- maybeFetchEnr(peer, maybeRemoteEnrSeq)
                    } yield true

                  case None =>
                    completePong(peer, responded = false).as(false)
                }
          }
      }

    /** Check and modify the bonding state of the peer: if we're already bonding
      * return the Deferred result we can wait on, otherwise add a new Deferred
      * and return the current local ENR sequence we can ping with.
      */
    protected[v4] def initBond(peer: Peer[A]): Task[Either[BondingResults, ENRSeq]] =
      for {
        results <- BondingResults()
        decision <- stateRef.modify { state =>
          state.bondingResultsMap.get(peer) match {
            case Some(results) =>
              state -> Left(results)

            case None =>
              state.withBondingResults(peer, results) -> Right(state.enr.content.seq)
          }
        }
      } yield decision

    protected[v4] def updateLastPongTime(peer: Peer[A]): Task[Unit] =
      for {
        now <- currentTimeMillis
        _ <- stateRef.update { state =>
          state.withLastPongTimestamp(peer, now)
        }
      } yield ()

    /** Update the bonding state of the peer with the result,
      * notifying all potentially waiting bonding processes about the outcome.
      */
    protected[v4] def completePong(peer: Peer[A], responded: Boolean): Task[Unit] = {
      for {
        _ <- stateRef
          .modify { state =>
            val maybePongReceived = state.bondingResultsMap.get(peer).map(_.pongReceived)
            state.clearBondingResults(peer) -> maybePongReceived
          }
          .flatMap { maybePongReceived =>
            maybePongReceived.fold(Task.unit)(_.complete(responded))
          }
      } yield ()
    }

    /** Allow the remote peer to ping us during bonding, so that we can have a more
      * fungible expectation that if we send a message they will consider us bonded and
      * not ignore it.
      *
      * The deferred should be completed by the ping handler.
      */
    protected[v4] def awaitPing(peer: Peer[A]): Task[Unit] =
      stateRef.get
        .map { state =>
          state.bondingResultsMap.get(peer).map(_.pingReceived)
        }
        .flatMap { maybePingReceived =>
          maybePingReceived.fold(Task.unit)(_.get.timeoutTo(config.requestTimeout, Task.unit))
        }

    /** Complete any deferred we set up during a bonding process expecting a ping to arrive. */
    protected[v4] def completePing(peer: Peer[A]): Task[Unit] =
      stateRef.get
        .map { state =>
          state.bondingResultsMap.get(peer).map(_.pingReceived)
        }
        .flatMap { maybePingReceived =>
          maybePingReceived.fold(Task.unit)(_.complete(()).attempt.void)
        }

    /** Fetch the remote ENR if we don't already have it or if
      * the sequence number we have is less than what we got just now.  */
    protected[v4] def maybeFetchEnr(
        peer: Peer[A],
        maybeRemoteEnrSeq: Option[ENRSeq]
    ): Task[Unit] =
      for {
        maybeEnrAndNode <- stateRef.get.map { state =>
          (state.enrMap.get(peer.id), state.nodeMap.get(peer.id))
        }
        needsFetching = maybeEnrAndNode match {
          case (None, _) =>
            true
          case (Some(enr), _) if maybeRemoteEnrSeq.getOrElse(enr.content.seq) > enr.content.seq =>
            true
          case (_, Some(node)) if toAddress(node.address) != peer.address =>
            true
          case _ =>
            false
        }
        _ <- fetchEnr(peer).whenA(needsFetching)
      } yield ()

    /** Fetch a fresh ENR from the peer and store it. */
    protected[v4] def fetchEnr(peer: Peer[A]): Task[Unit] = {
      val waitOrFetch =
        for {
          d <- Deferred[Task, Unit]
          decision <- stateRef.modify { state =>
            state.fetchEnrMap.get(peer) match {
              case Some(d) =>
                state -> Left(d)
              case None =>
                state.withEnrFetch(peer, d) -> Right(d)
            }
          }
        } yield decision

      waitOrFetch.flatMap {
        case Left(wait) =>
          wait.get

        case Right(fetch) =>
          rpc
            .enrRequest(peer)(())
            .flatMap {
              case None =>
                // Without an ENR we can't contact the node, and we shouldn't consider it bonded either
                // because we'll respond to it but we can't use it in discovery. Can try bonding again later.
                Task(logger.warn(s"Could not fetch ENR from ${peer.address}")) >>
                  isEnrFetched(peer).ifM(Task.unit, removePeer(peer))

              case Some(enr) =>
                EthereumNodeRecord.validateSignature(enr, publicKey = peer.id) match {
                  case Attempt.Successful(true) =>
                    tryUpdateEnr(peer, enr)

                  case Attempt.Successful(false) =>
                    Task(logger.debug("Could not validate ENR signature!")) >>
                      removePeer(peer)

                  case Attempt.Failure(err) =>
                    Task(logger.error(s"Error validateing ENR: $err"))
                }
            }
            .guarantee {
              stateRef.update(_.clearEnrFetch(peer)) >>
                fetch.complete(())
            }
      }
    }

    protected[v4] def isEnrFetched(peer: Peer[A]): Task[Boolean] =
      stateRef.get.map(_.enrMap.contains(peer.id))

    /** Try to extract the node address from the ENR record and update the node database,
      * otherwise if there's no address we can use remove the peer.
      */
    protected[v4] def tryUpdateEnr(peer: Peer[A], enr: EthereumNodeRecord): Task[Unit] =
      Node.Address.fromEnr(enr) match {
        case None =>
          Task(logger.debug(s"Could not extract node address from $enr")) >> removePeer(peer)
        case Some(address) =>
          stateRef.update(_.withEnrAndAddress(peer, enr, address))
      }

    /** Forget everything about this peer. */
    protected[v4] def removePeer(peer: Peer[A]): Task[Unit] =
      stateRef.update(_.removePeer(peer))
  }
}
