package io.iohk.scalanet.discovery.ethereum.v4

import cats.effect.{Clock, Resource}
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import io.iohk.scalanet.discovery.crypto.{PrivateKey, SigAlg}
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.kademlia.{KBuckets, XorOrdering}
import io.iohk.scalanet.peergroup.Addressable
import java.net.InetAddress
import monix.eval.Task
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scodec.{Codec, Attempt}
import scodec.bits.BitVector
import com.typesafe.scalalogging.LazyLogging
import scala.collection.immutable.SortedSet

/** Represent the minimal set of operations the rest of the system
  * can expect from the service to be able to talk to other peers.
  */
trait DiscoveryService {

  /** Try to look up a node either in the local cache or
    * by performing a recursive lookup on the network. */
  def getNode(nodeId: Node.Id): Task[Option[Node]]

  /** Return all currently bonded nodes. */
  def getNodes: Task[Set[Node]]

  /** Try to get the ENR record of the given node to add it to the cache. */
  def addNode(node: Node): Task[Unit]

  /** Remove a node from the local cache. */
  def removeNode(nodeId: Node.Id): Task[Unit]

  /** Update the local node with an updated external address,
    * incrementing the local ENR sequence.
    */
  def updateExternalAddress(ip: InetAddress): Task[Unit]

  /** The local node representation. */
  def getLocalNode: Task[Node]
}

object DiscoveryService {
  import DiscoveryRPC.{Call, Proc}
  import DiscoveryNetwork.Peer

  type ENRSeq = Long
  type Timestamp = Long
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
      privateKey: PrivateKey,
      node: Node,
      config: DiscoveryConfig,
      network: DiscoveryNetwork[A],
      toAddress: Node.Address => A,
      enrollInBackground: Boolean = false
  )(
      implicit sigalg: SigAlg,
      enrCodec: Codec[EthereumNodeRecord.Content],
      addressable: Addressable[A],
      clock: Clock[Task]
  ): Resource[Task, DiscoveryService] =
    Resource
      .make {
        for {
          _ <- checkKeySize("private key", privateKey, sigalg.PrivateKeyBytesSize)
          _ <- checkKeySize("node ID", node.id, sigalg.PublicKeyBytesSize)
          // Use the current time to set the ENR sequence to something fresh.
          now <- clock.monotonic(MILLISECONDS)
          enr <- Task(EthereumNodeRecord.fromNode(node, privateKey, seq = now).require)
          stateRef <- Ref[Task].of(State[A](node, enr))
          service <- Task(new ServiceImpl[A](privateKey, config, network, stateRef, toAddress))
          // Start handling requests, we need them during enrolling so the peers can ping and bond with us.
          cancelToken <- network.startHandling(service)
          // Contact the bootstrap nodes.
          enroll = service.enroll
          // Periodically discover new nodes.
          discover = service.lookupRandom.delayExecution(config.discoveryPeriod).loopForever
          // Enrollment can be run in the background if it takes very long.
          discoveryFiber <- if (enrollInBackground) {
            (enroll >> discover).start
          } else {
            enroll >> discover.start
          }
        } yield (service, cancelToken, discoveryFiber)
      } {
        case (_, cancelToken, discoveryFiber) =>
          cancelToken.cancel >> discoveryFiber.cancel
      }
      .map(_._1)

  protected[v4] def checkKeySize(name: String, key: BitVector, expectedBytesSize: Int): Task[Unit] =
    Task
      .raiseError(
        new IllegalArgumentException(
          s"Expected the $name to be ${expectedBytesSize} bytes; got ${key.size / 8} bytes."
        )
      )
      .whenA(key.size != expectedBytesSize * 8)

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
      // Kademlia buckets with hashes of the nodes' IDs in them.
      kBuckets: KBuckets[Hash],
      kademliaIdToNodeId: Map[Hash, Node.Id],
      nodeMap: Map[Node.Id, Node],
      enrMap: Map[Node.Id, EthereumNodeRecord],
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

    def withEnrAndAddress(
        peer: Peer[A],
        enr: EthereumNodeRecord,
        address: Node.Address,
        addToBucket: Boolean = true
    ): State[A] = {
      copy(
        enrMap = enrMap.updated(peer.id, enr),
        nodeMap = nodeMap.updated(peer.id, Node(peer.id, address)),
        kBuckets =
          if (isSelf(peer))
            kBuckets
          else if (kBuckets.getBucket(peer.kademliaId)._2.contains(peer.kademliaId))
            kBuckets.touch(peer.kademliaId)
          else if (addToBucket)
            kBuckets.add(peer.kademliaId)
          else
            kBuckets,
        kademliaIdToNodeId = kademliaIdToNodeId.updated(peer.kademliaId, peer.id)
      )
    }

    def withTouch(peer: Peer[A]): State[A] =
      copy(kBuckets = kBuckets.touch(peer.kademliaId))

    def clearBondingResults(peer: Peer[A]): State[A] =
      copy(bondingResultsMap = bondingResultsMap - peer)

    def withEnrFetch(peer: Peer[A], result: FetchEnrResult): State[A] =
      copy(fetchEnrMap = fetchEnrMap.updated(peer, result))

    def clearEnrFetch(peer: Peer[A]): State[A] =
      copy(fetchEnrMap = fetchEnrMap - peer)

    def removePeer(peer: Peer[A], toAddress: Node.Address => A): State[A] = {
      // We'll have ony one node/enr for this peer ID, but it may be with a different address.
      // This can happen if we get a fake neighbor respose from a malicious peer, with the ID
      // of an honest node and an ureachable address. We shouldn't remote the honest node.
      (nodeMap.get(peer.id) match {
        case Some(node) if toAddress(node.address) == peer.address =>
          copy(
            nodeMap = nodeMap - peer.id,
            enrMap = enrMap - peer.id,
            kBuckets = kBuckets.remove(peer.kademliaId),
            kademliaIdToNodeId = kademliaIdToNodeId - peer.kademliaId
          )
        case _ => this
      }).copy(
        // We can always remove these entries as they are keyed by ID+Address.
        lastPongTimestampMap = lastPongTimestampMap - peer,
        bondingResultsMap = bondingResultsMap - peer
      )
    }

    def removePeer(peerId: Node.Id): State[A] =
      copy(
        nodeMap = nodeMap - peerId,
        enrMap = enrMap - peerId,
        lastPongTimestampMap = lastPongTimestampMap.filterNot {
          case (peer, _) => peer.id == peerId
        },
        bondingResultsMap = bondingResultsMap.filterNot {
          case (peer, _) => peer.id == peerId
        },
        kBuckets = kBuckets.remove(Node.kademliaId(peerId)),
        kademliaIdToNodeId = kademliaIdToNodeId - Node.kademliaId(peerId)
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
      kBuckets = new KBuckets[Hash](node.kademliaId, clock),
      kademliaIdToNodeId = Map(node.kademliaId -> node.id),
      nodeMap = Map(node.id -> node),
      enrMap = Map(node.id -> enr),
      lastPongTimestampMap = Map.empty[Peer[A], Timestamp],
      bondingResultsMap = Map.empty[Peer[A], BondingResults],
      fetchEnrMap = Map.empty[Peer[A], FetchEnrResult]
    )
  }

  protected[v4] class ServiceImpl[A](
      privateKey: PrivateKey,
      config: DiscoveryConfig,
      rpc: DiscoveryRPC[Peer[A]],
      stateRef: StateRef[A],
      toAddress: Node.Address => A
  )(
      implicit clock: Clock[Task],
      sigalg: SigAlg,
      enrCodec: Codec[EthereumNodeRecord.Content],
      addressable: Addressable[A]
  ) extends DiscoveryService
      with DiscoveryRPC[Peer[A]]
      with LazyLogging {

    override def getLocalNode: Task[Node] =
      stateRef.get.map(_.node)

    override def addNode(node: Node): Task[Unit] =
      maybeFetchEnr(toPeer(node), None)

    override def getNodes: Task[Set[Node]] =
      stateRef.get.map(_.nodeMap.values.toSet)

    override def getNode(nodeId: Node.Id): Task[Option[Node]] =
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

    override def removeNode(nodeId: Node.Id): Task[Unit] =
      stateRef.update { state =>
        if (state.node.id == nodeId) state else state.removePeer(nodeId)
      }

    /** Update the node and ENR of the local peer with the new address and ping peers with the new ENR seq. */
    override def updateExternalAddress(ip: InetAddress): Task[Unit] = {
      stateRef
        .modify { state =>
          val node = Node(
            state.node.id,
            Node.Address(ip, udpPort = state.node.address.udpPort, tcpPort = state.node.address.tcpPort)
          )
          if (node == state.node)
            state -> Nil
          else {
            val enr = EthereumNodeRecord.fromNode(node, privateKey, state.enr.content.seq + 1).require
            val notify = state.lastPongTimestampMap.keySet.toList
            state.copy(
              node = node,
              enr = enr,
              nodeMap = state.nodeMap.updated(node.id, node),
              enrMap = state.enrMap.updated(node.id, enr)
            ) -> notify
          }
        }
        .flatMap { peers =>
          // Send our new ENR sequence to the peers so they can pull our latest data.
          Task.parTraverseN(config.kademliaAlpha)(peers)(pingAndMaybeUpdateTimestamp).startAndForget
        }
    }

    /** Handle incoming Ping request. */
    override def ping: Call[Peer[A], Proc.Ping] =
      caller =>
        maybeRemoteEnrSeq =>
          for {
            // Complete any deferred waiting for a ping from this peer, if we initiated the bonding.
            _ <- completePing(caller)
            _ <- isBonded(caller)
              .ifM(
                // We may already be bonded but the remote node could have changed its address.
                // It is possible that this is happening during a bonding, in which case we should
                // wait for our Pong response to get to the remote node and be processed first.
                maybeFetchEnr(caller, maybeRemoteEnrSeq, delay = true),
                // Try to bond back, if this is a new node.
                bond(caller)
              )
              .startAndForget
            // Return the latet local ENR sequence.
            enrSeq <- localEnrSeq
          } yield Some(Some(enrSeq))

    /** Handle incoming FindNode request. */
    override def findNode: Call[Peer[A], Proc.FindNode] =
      caller =>
        target =>
          respondIfBonded(caller, "FindNode") {
            for {
              state <- stateRef.get
              targetId = Node.kademliaId(target)
              closestNodeIds = state.kBuckets.closestNodes(targetId, config.kademliaBucketSize)
              closestNodes = closestNodeIds
                .map(state.kademliaIdToNodeId)
                .map(state.nodeMap)
            } yield closestNodes
          }

    /** Handle incoming ENRRequest. */
    override def enrRequest: Call[Peer[A], Proc.ENRRequest] =
      caller => _ => respondIfBonded(caller, "ENRRequest")(stateRef.get.map(_.enr))

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

    /** Return Some response if the peer is bonded or log some hint about what was requested and return None. */
    protected[v4] def respondIfBonded[T](caller: Peer[A], request: String)(response: Task[T]): Task[Option[T]] =
      isBonded(caller).flatMap {
        case true => response.map(Some(_))
        case false => Task(logger.debug(s"Ignoring $request request from unbonded $caller")).as(None)
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
    protected[v4] def bond(
        peer: Peer[A]
    ): Task[Boolean] =
      isBonded(peer).flatMap {
        case true =>
          // Check that we have an ENR for this peer.
          maybeFetchEnr(peer, maybeRemoteEnrSeq = None, delay = false).startAndForget.as(true)

        case false =>
          initBond(peer).flatMap {
            case Some(result) =>
              result.pongReceived.get.timeoutTo(config.requestTimeout, Task.pure(false))

            case None =>
              Task(logger.debug(s"Trying to bond with $peer...")) >>
                pingAndMaybeUpdateTimestamp(peer)
                  .flatMap {
                    case Some(maybeRemoteEnrSeq) =>
                      for {
                        _ <- Task(logger.debug(s"$peer responded to bond attempt."))
                        // Allow some time for the reciprocating ping to arrive.
                        _ <- awaitPing(peer)
                        // Complete all bonds waiting on this pong, after any pings were received
                        // so that we can now try and send requests with as much confidence as we can get.
                        _ <- completePong(peer, responded = true)
                        // We need the ENR record for the full address to be verified.
                        // First allow some time for our Pong to go back to the caller.
                        _ <- maybeFetchEnr(peer, maybeRemoteEnrSeq, delay = true).startAndForget
                      } yield true

                    case None =>
                      for {
                        _ <- Task(logger.debug(s"$peer did not respond to bond attempt."))
                        _ <- removePeer(peer)
                        _ <- completePong(peer, responded = false)
                      } yield false
                  }
                  .guarantee(stateRef.update(_.clearBondingResults(peer)))
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
      currentTimeMillis.flatMap { now =>
        stateRef.update { state =>
          state.withLastPongTimestamp(peer, now)
        }
      }

    /** Update the bonding state of the peer with the result,
      * notifying all potentially waiting bonding processes about the outcome.
      */
    protected[v4] def completePong(peer: Peer[A], responded: Boolean): Task[Unit] =
      stateRef
        .modify { state =>
          val maybePongReceived = state.bondingResultsMap.get(peer).map(_.pongReceived)
          state.clearBondingResults(peer) -> maybePongReceived
        }
        .flatMap { maybePongReceived =>
          maybePongReceived.fold(Task.unit)(_.complete(responded))
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
      * the sequence number we have is less than what we got just now.
      *
      * The execution might be delayed in case we are expecting the other side
      * to receive our Pong first, lest they think we are unbonded.
      * Passing on the variable so the Deferred is entered into the state.
      *  */
    protected[v4] def maybeFetchEnr(
        peer: Peer[A],
        maybeRemoteEnrSeq: Option[ENRSeq],
        delay: Boolean = false
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
        _ <- fetchEnr(peer, delay).whenA(needsFetching)
      } yield ()

    /** Fetch a fresh ENR from the peer and store it.
      *
      * Use delay=true if there's a high chance that the other side is still bonding with us
      * and hasn't received our Pong yet, in which case they'd ignore the ENRRequest.
      */
    protected[v4] def fetchEnr(
        peer: Peer[A],
        delay: Boolean = false
    ): Task[Option[EthereumNodeRecord]] = {
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
          wait.get.timeoutTo(config.requestTimeout, Task.pure(None))

        case Right(fetch) =>
          val maybeEnr = bond(peer).flatMap {
            case false =>
              Task(logger.debug(s"Could not bond with $peer to fetch ENR")).as(None)
            case true =>
              Task(logger.debug(s"Fetching the ENR from $peer...")) >>
                rpc
                  .enrRequest(peer)(())
                  .delayExecution(if (delay) config.requestTimeout else Duration.Zero)
                  .flatMap {
                    case None =>
                      // At this point we are still bonded with the peer, so they think they can send us requests.
                      // We just have to keep trying to get an ENR for them, until then we can't use them for routing.
                      Task(logger.debug(s"Could not fetch ENR from $peer")).as(None)

                    case Some(enr) =>
                      EthereumNodeRecord.validateSignature(enr, publicKey = peer.id) match {
                        case Attempt.Successful(true) =>
                          // Try to extract the node address from the ENR record and update the node database,
                          // otherwise if there's no address we can use remove the peer.
                          Node.Address.fromEnr(enr) match {
                            case None =>
                              Task(logger.debug(s"Could not extract node address from ENR $enr")) >>
                                removePeer(peer).as(None)

                            case Some(address) if !address.checkRelay(peer) =>
                              Task(logger.debug(s"Ignoring ENR with $address from $peer because of invalid relay IP.")) >>
                                removePeer(peer).as(None)

                            case Some(address) =>
                              Task(logger.info(s"Storing the ENR for $peer")) >>
                                storePeer(peer, enr, address)
                          }

                        case Attempt.Successful(false) =>
                          Task(logger.info(s"Could not validate ENR signature from $peer!")) >>
                            removePeer(peer).as(None)

                        case Attempt.Failure(err) =>
                          Task(logger.error(s"Error validating ENR from $peer: $err")).as(None)
                      }
                  }
          }

          maybeEnr
            .recoverWith {
              case NonFatal(ex) =>
                Task(logger.debug(s"Failed not fetch ENR from $peer: $ex")).as(None)
            }
            .flatTap(fetch.complete)
            .guarantee(stateRef.update(_.clearEnrFetch(peer)))
      }
    }

    /** Add the peer to the node and ENR maps, then see if the bucket the node would fit into isn't already full.
      * If it isn't, add the peer to the routing table, otherwise try to evict the least recently seen peer.
      *
      * Returns None if the routing record was discarded or Some if it was added to the k-buckets.
      *
      * NOTE: Half of the network falls in the first bucket, so we only track `k` of them. If we used
      * this component for routing messages it would be a waste to discard the ENR and use `lookup`
      * every time we need to talk to someone on the other half of the address space, so the ENR is
      * stored regardless of whether we enter the record in the k-buckets.
      */
    protected[v4] def storePeer(
        peer: Peer[A],
        enr: EthereumNodeRecord,
        address: Node.Address
    ): Task[Option[EthereumNodeRecord]] = {
      stateRef
        .modify { state =>
          if (state.isSelf(peer))
            state -> None
          else {
            val (_, bucket) = state.kBuckets.getBucket(peer.kademliaId)
            val (addToBucket, maybeEvict) =
              if (bucket.contains(peer.kademliaId) || bucket.size < config.kademliaBucketSize) {
                // We can just update the records, the bucket either has room or won't need to grow.
                true -> None
              } else {
                // We have to consider evicting somebody or dropping this node.
                false -> Some(state.nodeMap(state.kademliaIdToNodeId(bucket.head)))
              }
            // Store the ENR record and maybe update the k-buckets.
            state.withEnrAndAddress(peer, enr, address, addToBucket) -> maybeEvict
          }
        }
        .flatMap {
          case None =>
            Task(logger.debug(s"Added $peer to the k-buckets.")).as(Some(enr))

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
                Task(logger.debug(s"Not adding $peer to the k-buckets, keeping $evictionPeer")) >>
                  stateRef.update(_.withTouch(evictionPeer)).as(None)

              case false =>
                // Get rid of the non-responding peer and add the new one
                // then try to add this one again (something else might be trying as well,
                // don't want to end up overfilling the bucket).
                Task(logger.debug(s"Evicting $evictionPeer, maybe replacing with $peer")) >>
                  removePeer(evictionPeer) >>
                  storePeer(peer, enr, address)
            }
        }
    }

    /** Forget everything about this peer. */
    protected[v4] def removePeer(peer: Peer[A]): Task[Unit] =
      stateRef.update(_.removePeer(peer, toAddress))

    /** Locate the k closest nodes to a node ID.
      *
      * Note that it keeps going even if we know the target or find it along the way.
      * Due to the way it allows by default 7 seconds for the k closest neihbors to
      * arrive from each peer we ask (or if they return k quicker then it returns earlier)
      * it could be quite slow if it was used for routing.
      *
      * https://github.com/ethereum/devp2p/blob/master/discv4.md#recursive-lookup
      */
    protected[v4] def lookup(target: Node.Id): Task[SortedSet[Node]] = {
      val targetId = Node.kademliaId(target)

      implicit val nodeOrdering: Ordering[Node] =
        XorOrdering[Node, Hash](_.kademliaId)(targetId)

      // Find the 16 closest nodes we know of.
      // We'll contact 'alpha' at a time but eventually try all of them
      // unless better candidates are found.
      val init = for {
        _ <- checkKeySize("target public key", target, sigalg.PublicKeyBytesSize)

        state <- stateRef.get

        closestIds = state.kBuckets
          .closestNodes(targetId, config.kademliaBucketSize)

        closestNodes = closestIds.map(state.kademliaIdToNodeId).map(state.nodeMap)

        // In case we haven't been able to bond with the bootstrap nodes at startup,
        // and we don't have enough nodes to contact now, try them again, maybe
        // they are online now. This is so that we don't have to pretend they
        // are online and store them in the ENR map until they really are available.
        closestOrBootstraps = if (closestNodes.size < config.kademliaBucketSize)
          (closestNodes ++ config.knownPeers).distinct.take(config.kademliaBucketSize)
        else closestNodes

      } yield (state.node, closestOrBootstraps)

      def fetchNeighbors(from: Node): Task[List[Node]] = {
        val peer = toPeer(from)

        bond(peer).flatMap {
          case true =>
            rpc
              .findNode(peer)(target)
              .flatMap {
                case None =>
                  Task(logger.debug(s"Received no response for neighbors for $target from ${peer.address}")).as(Nil)
                case Some(neighbors) =>
                  Task(logger.debug(s"Received ${neighbors.size} neighbors for $target from ${peer.address}"))
                    .as(neighbors.toList)
              }
              .flatMap { neighbors =>
                neighbors.filterA { neighbor =>
                  if (neighbor.address.checkRelay(peer))
                    Task.pure(true)
                  else
                    Task(logger.debug(s"Ignoring neighbor $neighbor from ${peer.address} because of invalid relay IP."))
                      .as(false)
                }
              }
              .recoverWith {
                case NonFatal(ex) =>
                  Task(logger.debug(s"Failed to fetch neighbors of $target from ${peer.address}: $ex")).as(Nil)
              }
          case false =>
            Task(logger.debug(s"Could not bond with ${peer.address} to fetch neighbors of $target")).as(Nil)
        }
      }

      // Make sure these new nodes can be bonded with before we consider them,
      // otherwise they might appear to be be closer to the target but actually
      // be fakes with unreachable addresses that could knock out legit nodes.
      def bondNeighbors(neighbors: Seq[Node]): Task[Seq[Node]] =
        for {
          _ <- Task(logger.debug(s"Bonding with ${neighbors.size} neighbors..."))
          bonded <- Task
            .parTraverseUnordered(neighbors) { neighbor =>
              bond(toPeer(neighbor)).flatMap {
                case true =>
                  Task.pure(Some(neighbor))
                case false =>
                  Task(logger.debug(s"Could not bond with neighbor candidate $neighbor")).as(None)
              }
            }
            .map(_.flatten)
          _ <- Task(logger.debug(s"Bonded with ${bonded.size} neighbors out of ${neighbors.size}."))
        } yield bonded

      def loop(
          local: Node,
          closest: SortedSet[Node],
          asked: Set[Node],
          neighbors: Set[Node]
      ): Task[SortedSet[Node]] = {
        // Contact the alpha closest nodes to the target that we haven't asked before.
        val contacts = closest
          .filterNot(asked)
          .filterNot(_.id == local.id)
          .take(config.kademliaAlpha)
          .toList

        if (contacts.isEmpty) {
          Task(
            logger.debug(s"Lookup for $target finished; asked ${asked.size} nodes, found ${neighbors.size} neighbors.")
          ).as(closest)
        } else {
          Task(
            logger.debug(s"Lookup for $target contacting ${contacts.size} new nodes; asked ${asked.size} nodes so far.")
          ) >>
            Task
              .parTraverseUnordered(contacts)(fetchNeighbors)
              .map(_.flatten.distinct.filterNot(neighbors))
              .flatMap(bondNeighbors)
              .flatMap { newNeighbors =>
                val nextClosest = (closest ++ newNeighbors).take(config.kademliaBucketSize)
                val nextAsked = asked ++ contacts
                val nextNeighbors = neighbors ++ newNeighbors
                val newClosest = nextClosest diff closest
                Task(logger.debug(s"Lookup for $target found ${newClosest.size} neighbors closer than before.")) >>
                  loop(local, nextClosest, nextAsked, nextNeighbors)
              }
        }
      }

      init.flatMap {
        case (localNode, closestNodes) =>
          loop(localNode, closest = SortedSet(closestNodes: _*), asked = Set(localNode), neighbors = closestNodes.toSet)
      }
    }

    /** Look up a random node ID to discover new peers. */
    protected[v4] def lookupRandom: Task[Unit] =
      Task(logger.info("Looking up a random target...")) >>
        lookup(target = sigalg.newKeyPair._1).void

    /** Look up self with the bootstrap nodes. First we have to fetch their ENR
      * records to verify they are reachable and so that they can participate
      * in the lookup.
      *
      * Return `true` if we managed to get the ENR with at least one boostrap
      * or `false` if none of them responded with a correct ENR,
      * which would mean we don't have anyone to do lookups with.
      */
    protected[v4] def enroll: Task[Boolean] =
      if (config.knownPeers.isEmpty)
        Task.pure(false)
      else {
        for {
          nodeId <- stateRef.get.map(_.node.id)
          bootstrapPeers = config.knownPeers.toList.map(toPeer).filterNot(_.id == nodeId)
          _ <- Task(logger.info(s"Enrolling with ${bootstrapPeers.size} bootstrap nodes."))
          maybeBootstrapEnrs <- Task.parTraverseUnordered(bootstrapPeers)(fetchEnr(_, delay = true))
          enrolled = maybeBootstrapEnrs.count(_.isDefined)
          succeeded = enrolled > 0
          _ <- if (succeeded) {
            for {
              _ <- Task(
                logger.info(s"Successfully enrolled with $enrolled bootstrap nodes. Performing initial lookup...")
              )
              _ <- lookup(nodeId).doOnFinish {
                _.fold(Task.unit)(ex => Task(logger.error(s"Error during initial lookup", ex)))
              }
              nodeCount <- stateRef.get.map(_.nodeMap.size)
              _ <- Task(logger.info(s"Discovered $nodeCount nodes by the end of the lookup."))
            } yield ()
          } else {
            Task(logger.warn("Failed to enroll with any of the the bootstrap nodes."))
          }
        } yield succeeded
      }
  }
}
