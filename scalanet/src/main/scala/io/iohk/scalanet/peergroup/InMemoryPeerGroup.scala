package io.iohk.scalanet.peergroup

import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.InMemoryPeerGroup._
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.observables.ConnectableObservable

import scala.collection.concurrent.TrieMap

class InMemoryPeerGroup[A, M](address: A)(implicit network: Network[A, M]) extends PeerGroup[A, M] {

  private[peergroup] var status: PeerStatus = PeerStatus.NotInitialized
  private[peergroup] val channelStream = ConnectableSubject[ServerEvent[A, M]]()

  private[peergroup] val channelsMap: TrieMap[ChannelID, InMemoryChannel[A, M]] = TrieMap()

  def receiveMessage(channelID: ChannelID, from: A, msg: M): Unit = {
    channelsMap.get(channelID) match {
      case None =>
        val newChannel = new InMemoryChannel(channelID, processAddress, from)
        channelsMap += (channelID -> newChannel)
        newChannel.depositMessage(msg)
        channelStream.onNext(ChannelCreated(newChannel))
      case Some(ch) =>
        ch.depositMessage(msg)
    }
  }

  // Public interface
  override def processAddress: A = address
  override def initialize(): Result[Unit] =
    if (status == PeerStatus.Listening) error("Peer already connected")
    else {
      status = PeerStatus.Listening
      network.register(this)
    }
  override def client(to: A): Result[InMemoryChannel[A, M]] = {
    val channelID = newChannelID()
    val newChannel = new InMemoryChannel[A, M](channelID, processAddress, to)
    channelsMap += (channelID -> newChannel)
    allGood(newChannel)
  }
  override def server(): ConnectableObservable[ServerEvent[A, M]] = {
    status match {
      case PeerStatus.NotInitialized => throw new Exception(s"Peer $processAddress is not initialized yet")
      case PeerStatus.Listening => channelStream
      case PeerStatus.ShutDowned => throw new Exception(s"Peer $processAddress was shut downed")
    }
  }
  override def shutdown(): Result[Unit] = {
    status match {
      case PeerStatus.NotInitialized =>
        allGood(()) // Should we return an error here?
      case PeerStatus.Listening =>
        status = PeerStatus.ShutDowned
        network.disconnect(this)
        allGood(())
      case PeerStatus.ShutDowned =>
        error("The peer group was already down")
    }
  }
}

object InMemoryPeerGroup {

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
    case object Opened extends ChannelStatus
    case object Closed extends ChannelStatus
  }

  type Result[A] = Task[A]
  def allGood[A](x: A): Result[A] = Task.now(x)
  def error(msg: String): Result[Nothing] = Task.raiseError(new Exception(msg))

  // Reference Channel trait
  class InMemoryChannel[A, M](channelID: ChannelID, myAddress: A, destination: A)(implicit network: Network[A, M])
      extends Channel[A, M] {
    private var channelStatus: ChannelStatus = ChannelStatus.Opened
    private val messagesQueue = ConnectableSubject[M]()

    private[InMemoryPeerGroup] def depositMessage(m: M): Unit = messagesQueue.onNext(m)

    // Public interface
    override def to: A = destination
    override def sendMessage(message: M): Result[Unit] = network.deliverMessage(channelID, myAddress, to, message)
    override def in: ConnectableObservable[M] = messagesQueue
    override def close(): Result[Unit] = {
      // Note, what else should be done by close method? E.g. block messages that come to `in`?
      // what should happen to the other side of the channel?
      channelStatus match {
        case ChannelStatus.Opened =>
          channelStatus = ChannelStatus.Closed
          allGood(())
        case ChannelStatus.Closed =>
          error("Channel was already closed")
      }
    }
  }
}
