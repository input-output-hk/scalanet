package io.iohk.scalanet.peergroup

import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco._
import io.iohk.scalanet.monix_subjects.PublishToStrictlyOneSubject
import io.iohk.scalanet.peergroup.PeerGroup.{HandshakeException, ServerEvent}
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.{ChannelCreated, HandshakeFailed}
import io.iohk.scalanet.peergroup.SimplePeerGroup.{Config, _}
import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Promise

/**
  * Another fairly trivial example of a higher-level peer group. This class
  * builds on SimplestPeerGroup providing to provide additionally:
  * 1. a simple enrollment process to discover and register with other nodes on the network.
  * 2. a basic multicasting implementation
  */
class SimplePeerGroup[A, AA, M](
    val config: Config[A, AA],
    underLyingPeerGroup: PeerGroup[AA, Either[ControlMessage[A, AA], M]]
)(implicit aCodec: Codec[A], aaCodec: Codec[AA], scheduler: Scheduler)
    extends PeerGroup[A, M] {

  private val log = LoggerFactory.getLogger(getClass)

  private val routingTable: mutable.Map[A, AA] =
    new ConcurrentHashMap[A, AA]().asScala
  private val multiCastTable: mutable.Map[A, List[AA]] =
    new ConcurrentHashMap[A, List[AA]]().asScala

  override def processAddress: A = config.processAddress

  override def client(to: A): Task[Channel[A, M]] = {
    val underlyingAddresses: List[AA] =
      if (routingTable.contains(to)) List(routingTable(to))
      else multiCastTable(to)

    for {
      underlyingChannels <- Task.gatherUnordered(underlyingAddresses.map(aa => underLyingPeerGroup.client(aa)))
    } yield new ChannelImpl(to, underlyingChannels)
  }

  override val server = PublishToStrictlyOneSubject[ServerEvent[A, M]]()

  private def reverseLookup: mutable.Map[AA, A] = routingTable.map(_.swap)

  override def shutdown(): Task[Unit] = underLyingPeerGroup.shutdown()

  override def initialize(): Task[Unit] = {
    val initializing = Promise[Unit]()
    Task {
      routingTable += processAddress -> underLyingPeerGroup.processAddress

      setupUnderlyingServerHandler(initializing)

      if (config.knownPeers.nonEmpty) {
        val (knownPeerAddress, knownPeerAddressUnderlying) =
          config.knownPeers.head
        routingTable += knownPeerAddress -> knownPeerAddressUnderlying
        sendEnrolMeMessage(knownPeerAddress, knownPeerAddressUnderlying)
      } else {
        debug(
          s"Initialized as bootstrap node with no known peers. Waiting for others to connect."
        )
        initializing.success(())
      }
    }.flatMap(_ => Task.fromFuture(initializing.future))
  }

  private def sendEnrolMeMessage(knownPeerAddress: A, knownPeerAddressUnderlying: AA): Unit = {
    debug(
      s"Sending EnrolMe message to $knownPeerAddress -> $knownPeerAddressUnderlying"
    )
    underLyingPeerGroup
      .client(knownPeerAddressUnderlying)
      .foreach(
        channel =>
          channel
            .sendMessage(
              Left(
                EnrolMe(
                  processAddress,
                  config.multicastAddresses,
                  underLyingPeerGroup.processAddress
                )
              )
            )
            .runToFuture
      )
  }

  private def setupUnderlyingServerHandler(
      initializing: Promise[Unit]
  ): CancelableFuture[Unit] = {
    underLyingPeerGroup
      .server()
      .foreach {
        case ChannelCreated(underlyingChannel) =>
          debug(
            s"Received a new channel with remote field ${underlyingChannel.to}"
          )
          if (!initializing.isCompleted) {

            underlyingChannel.in
              .collect {
                case Left(e: Enrolled[A, AA]) =>
                  routingTable.clear()
                  routingTable ++= e.routingTable
                  multiCastTable ++= e.multiCastTable
                  initializing.success(())
                  debug(s"Initialized peer at address '$processAddress' with config ${this.config}")
                  debug(s"Routing table entries: ${e.routingTable}")
                  debug(s"Multicast table entries: ${e.multiCastTable}")
              }
              .subscribe()
          } else {
            val a = reverseLookup(underlyingChannel.to)
            server.onNext(
              ChannelCreated(new ChannelImpl(a, List(underlyingChannel)))
            )
          }

        case HandshakeFailed(failure) =>
          server.onNext(
            HandshakeFailed[A, M](
              new HandshakeException[A](
                reverseLookup(failure.to),
                failure.cause
              )
            )
          )
      }
  }

  private class ChannelImpl(
      val to: A,
      underlyingChannels: List[Channel[AA, Either[ControlMessage[A, AA], M]]]
  ) extends Channel[A, M] {

    debug(s"Creating new channel to $to")

    override val in = PublishToStrictlyOneSubject[M]()

    underlyingChannels.foreach { channel =>
      channel.in.foreach {
        case Left(e: EnrolMe[A, AA]) =>
          handleEnrollment(e)
        case Left(e: Enrolled[A, AA]) =>
          throw new IllegalStateException(
            s"Received Enrolled message from ${e.address} after initialization complete."
          )
        case Right(message) =>
          debug(
            s"Processing inbound message from remote address $to to local address $processAddress, $message. I am $this"
          )
          in.onNext(message)
      }
    }

    override def sendMessage(message: M): Task[Unit] =
      for {
        _ <- Task(
          debug(
            s"sendMessage from local address '$processAddress' to remote address '$to', " +
              s"with individual channel addresses (${underlyingChannels.map(c => reverseLookup(c.to)).mkString(",")}) and message '$message'"
          )
        )
        _ <- Task.gatherUnordered(
          underlyingChannels.map(_.sendMessage(Right(message)))
        )
      } yield ()

    override def close(): Task[Unit] =
      Task.gatherUnordered(underlyingChannels.map(_.close())).map(_ => ())
  }

  private def handleEnrollment(enrolMe: EnrolMe[A, AA]): Unit = {
    debug(s"Handling EnrolMe $enrolMe")
    import enrolMe._

    routingTable += myAddress -> myUnderlyingAddress
    updateMulticastTable(multicastAddresses, myUnderlyingAddress)
    notifyPeer(myAddress, myUnderlyingAddress)
  }

  private def updateMulticastTable(multicastAddresses: Set[A], underlyingAddress: AA): Unit = {
    debug(
      s"Updating multicast table to add multicast addresses $multicastAddresses " +
        s"for peer ${reverseLookup(underlyingAddress)} (underlying address $underlyingAddress)"
    )
    multicastAddresses.foreach { multicastAddress: A =>
      val aas: List[AA] = multiCastTable.getOrElse(multicastAddress, List.empty)
      val newAddressMappings = aas :+ underlyingAddress
      debug(s"New multicast mapping defined $multicastAddress -> $newAddressMappings")
      multiCastTable.put(multicastAddress, newAddressMappings)
    }
    debug(s"Multicast table updated to $multicastTable2Str")
  }

  private def multicastTable2Str: String = {
    val friendly = multiCastTable.map { case (a, saa) => a -> saa.map(reverseLookup) }.mkString(",")
    val raw = multiCastTable.mkString(",")
    s"\nFriendly: $friendly\nRaw: $raw"
  }

  private def notifyPeer(address: A, underlyingAddress: AA): Unit = {
    val enrolledReply = Enrolled(
      address,
      underlyingAddress,
      routingTable.toMap,
      multiCastTable.toMap
    )
    underLyingPeerGroup
      .client(underlyingAddress)
      .foreach(channel => channel.sendMessage(Left(enrolledReply)).runToFuture)
  }

  private def debug(logMsg: String): Unit = {
    log.debug(s"@$processAddress $logMsg")
  }
}

object SimplePeerGroup {

  private[scalanet] sealed trait ControlMessage[A, AA]

  private[scalanet] case class EnrolMe[A, AA](myAddress: A, multicastAddresses: Set[A], myUnderlyingAddress: AA)
      extends ControlMessage[A, AA]

  private[scalanet] case class Enrolled[A, AA](
      address: A,
      underlyingAddress: AA,
      routingTable: Map[A, AA],
      multiCastTable: Map[A, List[AA]]
  ) extends ControlMessage[A, AA]

  case class Config[A, AA](processAddress: A, multicastAddresses: Set[A], knownPeers: Map[A, AA])
}
