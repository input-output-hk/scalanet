package io.iohk.scalanet.discovery.ethereum.v4

import cats.effect.{Clock, Resource}
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
import scala.collection.immutable.SortedSet
import io.iohk.scalanet.kademlia.XorOrdering
import scodec.bits.BitVector

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

  /** Try to get the ENR record of the given node to add it to the cache. */
  def addNode(node: Node): Task[Unit]

  /** Remove a node from the local cache. */
  def removeNode(nodeId: NodeId): Task[Unit]

  /** Update the local node with an updated external address,
    * incrementing the local ENR sequence.
    */
  def updateExternalAddress(address: InetAddress): Task[Unit]

  /** The local node representation. */
  def getLocalNode: Task[Node]
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
      bootstraps: Set[Node],
      toAddress: Node.Address => A
  )(implicit sigalg: SigAlg, enrCodec: Codec[EthereumNodeRecord.Content]): Resource[Task, DiscoveryService] =
    Resource
      .make {
        for {
          stateRef <- Ref[Task].of(State[A](node, enr))
          service <- Task(new ServiceImpl[A](network, stateRef, config, toAddress))
          // Start handling requests, we need them during enrolling so the peers can ping and bond with us.
          cancelToken <- network.startHandling(service)
          // Contact the bootstrap nodes.
          _ <- service.enroll(bootstraps).whenA(bootstraps.nonEmpty)
          // Periodically discover new nodes.
          discoveryFiber <- service.lookupRandom().delayExecution(config.discoveryPeriod).loopForever.start
          // TODO: Periodically ping peers before they become unbonded.
        } yield (service, cancelToken, discoveryFiber)
      } {
        case (_, cancelToken, discoveryFiber) =>
          cancelToken.cancel >> discoveryFiber.cancel
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

  protected[v4] type FetchEnrResult = Deferred[Task, Option[EthereumNodeRecord]]

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
      fetchEnrMap: Map[Peer[A], FetchEnrResult]
  ) {
    def isSelf(peer: Peer[A]): Boolean =
      peer.id == node.id

    def withLastPongTimestamp(peer: Peer[A], timestamp: Timestamp): State[A] =
      copy(lastPongTimestampMap = lastPongTimestampMap.updated(peer, timestamp))

    def withBondingResults(peer: Peer[A], results: BondingResults): State[A] =
      copy(bondingResultsMap = bondingResultsMap.updated(peer, results))

    def withEnrAndAddress(peer: Peer[A], enr: EthereumNodeRecord, address: Node.Address): State[A] =
      copy(
        enrMap = enrMap.updated(peer.id, enr),
        nodeMap = nodeMap.updated(peer.id, Node(peer.id, address)),
        kBuckets =
          if (isSelf(peer))
            kBuckets
          else if (kBuckets.getBucket(peer.id)._2.contains(peer.id))
            kBuckets.touch(peer.id)
          else
            kBuckets.add(peer.id)
      )

    def withTouch(peer: Peer[A]): State[A] =
      copy(kBuckets = kBuckets.touch(peer.id))

    def clearBondingResults(peer: Peer[A]): State[A] =
      copy(bondingResultsMap = bondingResultsMap - peer)

    def withEnrFetch(peer: Peer[A], result: FetchEnrResult): State[A] =
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
      fetchEnrMap = Map.empty[Peer[A], FetchEnrResult]
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

    override def getLocalNode: Task[Node] =
      stateRef.get.map(_.node)

    override def addNode(node: Node): Task[Unit] =
      maybeFetchEnr(toPeer(node), None)

    override def getNodes: Task[Set[Node]] =
      stateRef.get.map(_.nodeMap.values.toSet)

    override def getNode(nodeId: NodeId): Task[Option[Node]] =
      stateRef.get.flatMap { state =>
        state.nodeMap.get(nodeId) match {
          case cached @ Some(_) =>
            Task.pure(cached)
          case None =>
            lookup(nodeId).flatMap {
              case closest if closest.head.id == nodeId =>
                maybeFetchEnr(toPeer(closest.head), None) >>
                  stateRef.get.map(_.nodeMap.get(nodeId))
              case _ =>
                Task.pure(None)
            }
        }
      }

    override def removeNode(nodeId: NodeId): Task[Unit] = ???
    override def updateExternalAddress(address: InetAddress): Task[Unit] = ???

    /** Handle incoming Ping request. */
    override def ping: Call[Peer[A], Proc.Ping] =
      caller =>
        maybeRemoteEnrSeq =>
          for {
            // Complete any deferred waiting for a ping from this peer, if we initiated the bonding.
            _ <- completePing(caller)
            // Try to bond back, if this is a new node.
            _ <- bond(caller).startAndForget
            // We may already be bonded but the remote node could have changed its address.
            _ <- maybeFetchEnr(caller, maybeRemoteEnrSeq).startAndForget
            // Return the latet local ENR sequence.
            enrSeq <- localEnrSeq
          } yield Some(Some(enrSeq))

    /** Handle incoming FindNode request. */
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

    /** Handle incoming ENRRequest. */
    override def enrRequest: Call[Peer[A], Proc.ENRRequest] =
      caller => _ => ifBonded(caller)(stateRef.get.map(_.enr))

    // The methods below are `protected[v4]` so that they can be called from tests individually.
    // Initially they were in the companion object as pure functions but there are just too many
    // parameters to pass around.

    protected[v4] def toPeer(node: Node): Peer[A] =
      Peer(node.id, toAddress(node.address))

    protected[v4] def currentTimeMillis: Task[Long] =
      clock.realTime(MILLISECONDS)

    protected[v4] def localEnrSeq: Task[ENRSeq] =
      stateRef.get.map(_.enr.content.seq)

    /** Check if the given peer has a valid bond at the moment. */
    protected[v4] def isBonded(
        peer: Peer[A]
    ): Task[Boolean] = {
      currentTimeMillis.flatMap { now =>
        stateRef.get.map { state =>
          if (state.isSelf(peer))
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
            case Some(result) =>
              result.pongReceived.get

            case None =>
              pingAndMaybeUpdateTimestamp(peer)
                .flatMap {
                  case Some(maybeRemoteEnrSeq) =>
                    for {
                      // Allow some time for the reciprocating ping to arrive.
                      _ <- awaitPing(peer)
                      // Complete all bonds waiting on this pong, after any pings were received
                      // so that we can now try and send requests with as much confidence as we can get.
                      _ <- completePong(peer, responded = true)
                      // We need the ENR record for the full address to be verified.
                      _ <- maybeFetchEnr(peer, maybeRemoteEnrSeq).startAndForget
                    } yield true

                  case None =>
                    removePeer(peer) >>
                      completePong(peer, responded = false).as(false)
                }
          }
      }

    /** Try to ping the remote peer and update the last pong timestamp if they respond. */
    protected[v4] def pingAndMaybeUpdateTimestamp(peer: Peer[A]): Task[Option[Option[ENRSeq]]] =
      for {
        enrSeq <- localEnrSeq
        maybeResponse <- rpc.ping(peer)(Some(enrSeq)).recover {
          case NonFatal(_) => None
        }
        _ <- updateLastPongTime(peer).whenA(maybeResponse.isDefined)
      } yield maybeResponse

    /** Check and modify the bonding state of the peer: if we're already bonding
      * return the Deferred result we can wait on, otherwise add a new Deferred
      * and return None, in which case the caller has to perform the bonding.
      */
    protected[v4] def initBond(peer: Peer[A]): Task[Option[BondingResults]] =
      for {
        results <- BondingResults()
        maybeExistingResults <- stateRef.modify { state =>
          state.bondingResultsMap.get(peer) match {
            case Some(results) =>
              state -> Some(results)

            case None =>
              state.withBondingResults(peer, results) -> None
          }
        }
      } yield maybeExistingResults

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
        state <- stateRef.get
        maybeEnrAndNode = (state.enrMap.get(peer.id), state.nodeMap.get(peer.id))
        needsFetching = maybeEnrAndNode match {
          case _ if state.isSelf(peer) =>
            false
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
    protected[v4] def fetchEnr(peer: Peer[A]): Task[Option[EthereumNodeRecord]] = {
      val waitOrFetch =
        for {
          d <- Deferred[Task, Option[EthereumNodeRecord]]
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
          val maybeEnr = bond(peer).flatMap {
            case false =>
              Task(logger.warn(s"Could not bond with ${peer.address} to fetch ENR")).as(None)
            case true =>
              rpc
                .enrRequest(peer)(())
                .flatMap {
                  case None =>
                    // At this point we are still bonded with the peer, so they think they can send us requests.
                    // We just have to keep trying to get an ENR for them, until then we can't use them for routing.
                    Task(logger.warn(s"Could not fetch ENR from ${peer.address}")).as(None)

                  case Some(enr) =>
                    EthereumNodeRecord.validateSignature(enr, publicKey = peer.id) match {
                      case Attempt.Successful(true) =>
                        // Try to extract the node address from the ENR record and update the node database,
                        // otherwise if there's no address we can use remove the peer.
                        Node.Address.fromEnr(enr) match {
                          case None =>
                            Task(logger.debug(s"Could not extract node address from $enr")) >>
                              removePeer(peer).as(None)

                          case Some(address) =>
                            maybeStorePeer(peer, enr, address)
                        }

                      case Attempt.Successful(false) =>
                        Task(logger.debug("Could not validate ENR signature!")) >>
                          removePeer(peer).as(None)

                      case Attempt.Failure(err) =>
                        Task(logger.error(s"Error validateing ENR: $err")).as(None)
                    }
                }
          }

          maybeEnr
            .flatTap(fetch.complete)
            .guarantee(stateRef.update(_.clearEnrFetch(peer)))
      }
    }

    /** See if the bucket the node would fit into isn't already full. If it is, try to evict the least recently seen peer.
      * Returns None if the record was discarded or Some if it was stored.
      */
    protected[v4] def maybeStorePeer(
        peer: Peer[A],
        enr: EthereumNodeRecord,
        address: Node.Address
    ): Task[Option[EthereumNodeRecord]] = {
      stateRef
        .modify { state =>
          if (state.isSelf(peer))
            state -> None
          else {
            val (_, bucket) = state.kBuckets.getBucket(peer.id)
            if (bucket.contains(peer.id) || bucket.size < config.kademliaBucketSize) {
              // We can just update the records, the bucket either has room or won't need to grow.
              state.withEnrAndAddress(peer, enr, address) -> None
            } else {
              // We have to consider evicting somebody or dropping this node.
              state -> Some(state.nodeMap(PublicKey(bucket.head)))
            }
          }
        }
        .flatMap {
          case None =>
            Task.pure(Some(enr))

          case Some(evictionCandidate) =>
            val evictionPeer = toPeer(evictionCandidate)
            pingAndMaybeUpdateTimestamp(evictionPeer).map(_.isDefined).flatMap {
              case true =>
                // Keep the existing record, discard the new.
                // NOTE: We'll still consider them bonded because they reponded to a ping,
                // so we'll respond to queries and maybe even send requests in recursive
                // lookups, but won't return the peer itself in results.
                // A more sophisticated approach would be to put them in a separate replacement
                // cache for the bucket where they can be drafted from if someone cannot bond again.
                stateRef.update(_.withTouch(evictionPeer)).as(None)
              case false =>
                // Get rid of the non-responding peer and add the new one
                // then try to add this one again (something else might be trying as well,
                // don't want to end up overfilling the bucket).
                removePeer(evictionPeer) >> maybeStorePeer(peer, enr, address)
            }
        }
    }

    /** Forget everything about this peer. */
    protected[v4] def removePeer(peer: Peer[A]): Task[Unit] =
      stateRef.update(_.removePeer(peer))

    /** Locate the k closest nodes to a node ID.
      *
      * https://github.com/ethereum/devp2p/blob/master/discv4.md#recursive-lookup
      */
    protected[v4] def lookup(target: NodeId): Task[SortedSet[Node]] = {
      implicit val targetIdOrdering: Ordering[BitVector] =
        new XorOrdering(target)

      implicit val nodeOrder: Ordering[Node] =
        Ordering.by[Node, BitVector](node => node.id)

      // Find the 16 closest nodes we know of.
      // We'll contact 'alpha' at a time but eventually try all of them
      // unless better candidates are found.
      val init = for {
        state <- stateRef.get

        closestIds = state.kBuckets
          .closestNodes(target, config.kademliaBucketSize)
          .map(PublicKey(_))

        closestNodes = closestIds.map(state.nodeMap)
      } yield (state.node, closestNodes)

      def loop(
          local: Node,
          closest: SortedSet[Node],
          asked: Set[Node]
      ): Task[SortedSet[Node]] = {
        // Contact the alpha closest nodes to the target that we haven't asked before.
        val contact = closest
          .filterNot(asked)
          .filterNot(_.id == local.id)
          .take(config.kademliaAlpha)
          .toList

        if (contact.isEmpty)
          Task.pure(closest)
        else {
          Task
            .traverse(contact) { node =>
              val peer = toPeer(node)
              bond(peer).flatMap {
                case true =>
                  rpc.findNode(peer)(target).recoverWith {
                    case NonFatal(ex) =>
                      Task(logger.debug(s"Failed to fetch neighbors of $target from $node: $ex")).as(None)
                  }
                case false =>
                  Task(logger.debug(s"Could not bond with $node to fetch neighbors of $target")).as(None)
              }
            }
            .flatMap { results =>
              val newClosest = (closest ++ results.flatten.flatten).take(config.kademliaBucketSize)
              val newAsked = asked ++ contact
              loop(local, newClosest, newAsked)
            }
        }
      }

      init.flatMap {
        case (localNode, closestNodes) =>
          loop(localNode, SortedSet(closestNodes: _*), Set(localNode))
      }
    }

    /** Look up a random node ID to discovery new peers. */
    protected[v4] def lookupRandom(): Task[Unit] =
      lookup(target = sigalg.newKeyPair._1).void

    /** Look up self with the bootstrap nodes. First we have to fetch their ENR
      * records to verify they are reachable and so that they can participate
      * in the lookup.
      *
      * Return `true` if we managed to get the ENR with at least one boostrap
      * or `false` if none of them responded with a correct ENR,
      * which would mean we don't have anyone to do lookups with.
      */
    protected[v4] def enroll(boostrapNodes: Set[Node]): Task[Boolean] =
      if (boostrapNodes.isEmpty)
        Task.pure(false)
      else {
        val tryEnroll = for {
          nodeId <- stateRef.get.map(_.node.id)
          bootstrapPeers = boostrapNodes.toList.map(toPeer).filterNot(_.id == nodeId)
          maybeBootstrapEnrs <- bootstrapPeers.traverse(fetchEnr)
          result <- if (maybeBootstrapEnrs.exists(_.isDefined)) {
            lookup(nodeId).as(true)
          } else {
            Task.pure(false)
          }
        } yield result

        tryEnroll.flatTap {
          case true =>
            Task(logger.info("Successfully enrolled with the bootstrap nodes."))
          case false =>
            Task(logger.warn("Failed to enroll with the bootstrap nodes."))
        }
      }
  }
}
