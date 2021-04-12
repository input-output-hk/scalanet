package io.iohk.scalanet.discovery.ethereum.v4.mocks

import cats.effect.Resource
import io.iohk.scalanet.peergroup.PeerGroup
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.Channel.{ChannelEvent, MessageReceived}
import io.iohk.scalanet.peergroup.Channel
import monix.eval.Task
import monix.execution.{Scheduler, BufferCapacity}
import scala.collection.concurrent.TrieMap
import monix.execution.atomic.AtomicInt
import monix.catnap.ConcurrentQueue
import scala.concurrent.duration._

class MockPeerGroup[A, M](
    override val processAddress: A
)(implicit val s: Scheduler)
    extends PeerGroup[A, M] {

  private val channels = TrieMap.empty[A, MockChannel[A, M]]
  private val serverEvents = ConcurrentQueue[Task].unsafe[ServerEvent[A, M]](BufferCapacity.Unbounded())

  // Intended for the System Under Test to read incoming channels.
  override def nextServerEvent: Task[Option[PeerGroup.ServerEvent[A, M]]] =
    serverEvents.poll.map(Some(_))

  // Intended for the System Under Test to open outgoing channels.
  override def client(to: A): Resource[Task, Channel[A, M]] = {
    Resource.make(
      for {
        channel <- getOrCreateChannel(to)
        _ <- Task(channel.refCount.increment())
      } yield channel
    ) { channel =>
      Task(channel.refCount.decrement())
    }
  }

  def getOrCreateChannel(to: A): Task[MockChannel[A, M]] =
    Task(channels.getOrElseUpdate(to, new MockChannel[A, M](processAddress, to)))

  def createServerChannel(from: A): Task[MockChannel[A, M]] =
    for {
      channel <- Task(new MockChannel[A, M](processAddress, from))
      _ <- Task(channel.refCount.increment())
      event = ChannelCreated(channel, Task(channel.refCount.decrement()))
      _ <- serverEvents.offer(event)
    } yield channel
}

class MockChannel[A, M](
    override val from: A,
    override val to: A
)(implicit val s: Scheduler)
    extends Channel[A, M] {

  // In lieu of actually closing the channel,
  // just count how many times t was opened and released.
  val refCount = AtomicInt(0)

  private val messagesFromSUT = ConcurrentQueue[Task].unsafe[ChannelEvent[M]](BufferCapacity.Unbounded())
  private val messagesToSUT = ConcurrentQueue[Task].unsafe[ChannelEvent[M]](BufferCapacity.Unbounded())

  def isClosed: Boolean =
    refCount.get() == 0

  // Messages coming from the System Under Test.
  override def sendMessage(message: M): Task[Unit] =
    messagesFromSUT.offer(MessageReceived(message))

  // Messages consumed by the System Under Test.
  override def nextChannelEvent: Task[Option[Channel.ChannelEvent[M]]] =
    messagesToSUT.poll.map(Some(_))

  // Send a message from the test.
  def sendMessageToSUT(message: M): Task[Unit] =
    messagesToSUT.offer(MessageReceived(message))

  def nextMessageFromSUT(timeout: FiniteDuration = 250.millis): Task[Option[ChannelEvent[M]]] =
    messagesFromSUT.poll.map(Some(_)).timeoutTo(timeout, Task.pure(None))
}
