package io.iohk.scalanet.peergroup

import monix.eval.Task
import monix.reactive.{MulticastStrategy, Observable}
import monix.reactive.subjects.ConcurrentSubject
import monix.execution.Scheduler.Implicits.global
import InMemoryPeerGroup._

import scala.collection.concurrent.TrieMap
import scala.util.Random

// For simplicity we can model the network with three main interactions.
// 1. Register to the network.
// 2. Disconnect from the network.
// 3. Send messages.
class Network[A, M] {
  private val peers: TrieMap[A, InMemoryPeerGroup[A, M]] = TrieMap()

  def clear(): Unit = peers.clear()

  def register(peer: InMemoryPeerGroup[A, M]): Result[Unit] =
    if (peers.get(peer.processAddress).isEmpty) {
      peers += (peer.processAddress -> peer)
      allGood(())
    } else error("The address is already in use")

  def disconnect(peer: InMemoryPeerGroup[A, M]): Unit = peers -= peer.processAddress

  def deliverMessage(channelID: ChannelID, from: A, to: A, message: M): Result[Unit] =
    peers.get(to) match {
      case None => error(s"Unreachable peer $to")
      case Some(destination) =>
        allGood(destination.receiveMessage(channelID, from, message))
    }
}

class InMemoryPeerGroup[A, M](address: A)(implicit network: Network[A, M]) extends PeerGroup[A, M] {

  private var status: PeerStatus = PeerStatus.NotInitialized
  private val channelStream = ConcurrentSubject[InMemoryChannel[A, M]](MulticastStrategy.publish)
  private val channelsMap: TrieMap[ChannelID, InMemoryChannel[A, M]] = TrieMap()
  def receiveMessage(channelID: ChannelID, from: A, msg: M): Unit = {
    channelsMap.get(channelID) match {
      case None =>
        val newChannel = new InMemoryChannel(channelID, processAddress, from)
        channelsMap += (channelID -> newChannel)
        newChannel.depositMessage(msg)
        channelStream.onNext(newChannel)
      case Some(ch) =>
        ch.depositMessage(msg)
    }
  }

  // Public interface
  def processAddress: A = address
  def initialize(): Result[Unit] =
    if (status == PeerStatus.Listening) error("Peer already connected")
    else {
      status = PeerStatus.Listening
      network.register(this)
    }
  def client(to: A): Result[InMemoryChannel[A, M]] = {
    val channelID = newChannelID()
    val newChannel = new InMemoryChannel[A, M](channelID, processAddress, to)
    channelsMap += (channelID -> newChannel)
    allGood(newChannel)
  }
  def server(): Observable[InMemoryChannel[A, M]] = {
    status match {
      case PeerStatus.NotInitialized => throw new Exception(s"Peer $processAddress is not initialized yet")
      case PeerStatus.Listening => channelStream
      case PeerStatus.ShutDowned => throw new Exception(s"Peer $processAddress was shut downed")
    }
  }
  def shutdown(): Result[Unit] = {
    status match {
      case PeerStatus.NotInitialized =>
        allGood(()) // Should we return an error here?
      case PeerStatus.Listening =>
        status = PeerStatus.ShutDowned
        network.disconnect(this)
        allGood(())
      case PeerStatus.ShutDowned =>
        error("The peer group was already down") // Should we return an error here?
    }
  }
}

object InMemoryPeerGroup {

  implicit def inMemoryPeerGroupTestUtils(implicit n: Network[Int, String]): PeerUtils[Int, String, InMemoryPeerGroup] =
    PeerUtils.instance(
      _.status == PeerStatus.Listening,
      () => new InMemoryPeerGroup[Int, String](Random.nextInt()),
      _.shutdown(),
      _.initialize()
    )

  type ChannelID = java.util.UUID
  def newChannelID(): ChannelID = java.util.UUID.randomUUID()

  sealed trait PeerStatus
  object PeerStatus {
    case object NotInitialized extends PeerStatus
    case object Listening extends PeerStatus
    case object ShutDowned extends PeerStatus
  }

  trait ChannelStatus
  object ChannelStatus {
    case object Open extends ChannelStatus
    case object Close extends ChannelStatus
  }

  type Result[A] = Task[A]
  def allGood[A](x: A): Result[A] = Task.now(x)
  def error(msg: String): Result[Nothing] = Task.now(throw new Exception(msg))

  // Reference Channel trait
  class InMemoryChannel[A, M](channelID: ChannelID, myAddress: A, destination: A)(implicit network: Network[A, M])
      extends Channel[A, M] {
    private var channelStatus: ChannelStatus = ChannelStatus.Open
    private val messagesQueue = ConcurrentSubject[M](MulticastStrategy.replay)
    def depositMessage(m: M): Unit = messagesQueue.onNext(m)

    // Public interface
    def to: A = destination
    def sendMessage(message: M): Result[Unit] = network.deliverMessage(channelID, myAddress, to, message)
    def in: Observable[M] = messagesQueue
    def close(): Result[Unit] = {
      // Note, what else should be done by close method? E.g. block messages that come to `in`?
      // what should happen to the other side of the channel?
      channelStatus match {
        case ChannelStatus.Open =>
          channelStatus = ChannelStatus.Close
          allGood(())
        case ChannelStatus.Close =>
          error("Channel was already closed")
      }
    }
  }
}
