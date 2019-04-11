package io.iohk.scalanet.peergroup

import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.peergroup.SimplePeerGroup.Config

import scala.collection.mutable
import scala.collection.JavaConverters._
import io.iohk.decco._
import SimplePeerGroup._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

class SimplePeerGroup[A, AA, M](
    val config: Config[A, AA],
    underLyingPeerGroup: PeerGroup[AA, Either[ControlMessage[A, AA], M]]
)(
    implicit aCodec: Codec[A],
    aaCodec: Codec[AA],
    scheduler: Scheduler
) extends PeerGroup[A, M] {

  private val log = LoggerFactory.getLogger(getClass)

  private val routingTable: mutable.Map[A, AA] = new ConcurrentHashMap[A, AA]().asScala
  private val multiCastTable: mutable.Map[A, List[AA]] = new ConcurrentHashMap[A, List[AA]]().asScala

  private implicit val apc: PartialCodec[A] = aCodec.partialCodec
  private implicit val aapc: PartialCodec[AA] = aaCodec.partialCodec

  override def processAddress: A = config.processAddress

  override def client(to: A): Task[Channel[A, M]] =
    underLyingPeerGroup.client(routingTable(to)).map(underlyingChannel => new ChannelImpl(to, underlyingChannel))

  override def server(): Observable[Channel[A, M]] = underLyingPeerGroup.server().map {
    underlyingChannel: Channel[AA, Either[ControlMessage[A, AA], M]] =>
      val reverseLookup: mutable.Map[AA, A] = routingTable.map(_.swap)
      new ChannelImpl(reverseLookup(underlyingChannel.to), underlyingChannel)
  }

  override def shutdown(): Task[Unit] = underLyingPeerGroup.shutdown()

  override def initialize(): Task[Unit] = {
    routingTable += processAddress -> underLyingPeerGroup.processAddress

    underLyingPeerGroup
      .server()
      .flatMap(channel => channel.in)
      .collect {
        case Left(e: EnrolMe[A, AA]) => e
      }
      .foreach(handleEnrollment)

    if (config.knownPeers.nonEmpty) {
      val (knownPeerAddress, knownPeerAddressUnderlying) = config.knownPeers.head
      routingTable += knownPeerAddress -> knownPeerAddressUnderlying

      val enrolledTask: Task[Unit] = underLyingPeerGroup
        .server()
        .flatMap(channel => channel.in)
        .collect {
          case Left(e: Enrolled[A, AA]) =>
            routingTable.clear()
            routingTable ++= e.routingTable
            log.debug(
              s"Peer address '$processAddress' enrolled into group and installed new routing table:\n${e.routingTable}"
            )
        }
        .headL

      underLyingPeerGroup
        .client(knownPeerAddressUnderlying)
        .foreach(
          channel =>
            channel
              .sendMessage(Left(EnrolMe(processAddress, config.multicastAddresses, underLyingPeerGroup.processAddress)))
              .runToFuture
        )

      enrolledTask
    } else {
      Task.unit
    }
  }

  private class ChannelImpl(val to: A, underlyingChannel: Channel[AA, Either[ControlMessage[A, AA], M]])
      extends Channel[A, M] {

    override def sendMessage(message: M): Task[Unit] = underlyingChannel.sendMessage(Right(message))

    override def in: Observable[M] = underlyingChannel.in.collect {
      case Right(message) =>
        message
    }

    override def close(): Task[Unit] =
      underlyingChannel.close()
  }

  private def handleEnrollment(enrolMe: EnrolMe[A, AA]): Unit = {

    import enrolMe._

    routingTable += myAddress -> myUnderlyingAddress
    updateMulticastTable(multicastAddresses, myUnderlyingAddress)
    notifyPeer(myAddress, myUnderlyingAddress)
  }

  private def updateMulticastTable(multicastAddresses: List[A], underlyingAddress: AA): Unit = {
    multicastAddresses foreach { a =>
      val existingAddress: List[AA] = if (multiCastTable.contains(a)) {
        multiCastTable(a)
      } else Nil
      multiCastTable += a -> (existingAddress ::: List(underlyingAddress))
    }
  }

  private def notifyPeer(address: A, underlyingAddress: AA) = {
    val enrolledReply = Enrolled(address, underlyingAddress, routingTable.toMap, multiCastTable.toMap)
    underLyingPeerGroup
      .client(underlyingAddress)
      .foreach(channel => channel.sendMessage(Left(enrolledReply)).runToFuture)
  }
}

object SimplePeerGroup {

  private[scalanet] sealed trait ControlMessage[A, AA]

  private[scalanet] case class EnrolMe[A, AA](myAddress: A, multicastAddresses: List[A], myUnderlyingAddress: AA)
      extends ControlMessage[A, AA]

  private[scalanet] case class Enrolled[A, AA](
      address: A,
      underlyingAddress: AA,
      routingTable: Map[A, AA],
      multiCastTable: Map[A, List[AA]]
  ) extends ControlMessage[A, AA]

  case class Config[A, AA](processAddress: A, multicastAddresses: List[A], knownPeers: Map[A, AA])
}
