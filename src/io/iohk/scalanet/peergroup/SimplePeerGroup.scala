package io.iohk.scalanet.peergroup

import java.util.concurrent.ConcurrentHashMap

import io.iohk.scalanet.peergroup.PeerGroup.NonTerminalPeerGroup
import io.iohk.scalanet.peergroup.SimplePeerGroup.Config

import scala.collection.mutable
import scala.collection.JavaConverters._
import io.iohk.decco.auto._
import io.iohk.decco._
import SimplePeerGroup._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

class SimplePeerGroup[A, AA](
    val config: Config[A, AA],
    underLyingPeerGroup: PeerGroup[AA]
)(
    implicit aCodec: Codec[A],
    aaCodec: Codec[AA],
    scheduler: Scheduler
) extends NonTerminalPeerGroup[A, AA](underLyingPeerGroup) {

  private val log = LoggerFactory.getLogger(getClass)

  private val routingTable: mutable.Map[A, List[AA]] = new ConcurrentHashMap[A, List[AA]]().asScala

  private implicit val apc: PartialCodec[A] = aCodec.partialCodec
  private implicit val aapc: PartialCodec[AA] = aaCodec.partialCodec

  override val processAddress: A = config.processAddress

  underLyingPeerGroup
    .messageChannel[EnrolMe[A, AA]]
    .foreach {
      case (_, enrolMe) =>
        enrolMe.multiCastAddresses foreach { a =>
          val existingAddress: List[AA] = if (routingTable.contains(a)) {
            routingTable(a)
          } else Nil
          routingTable += a -> (existingAddress ::: List(enrolMe.myUnderlyingAddress))
        }
        routingTable += enrolMe.myAddress -> List(enrolMe.myUnderlyingAddress)
        log.debug(
          s"Processed enrolment message $enrolMe at address '$processAddress' with corresponding routing table update."
        )
        underLyingPeerGroup
          .sendMessage(
            enrolMe.myUnderlyingAddress,
            Enrolled(enrolMe.myAddress, enrolMe.myUnderlyingAddress, routingTable.toList)
          )
          .runToFuture
    }

  override def sendMessage[MessageType: Codec](address: A, message: MessageType): Task[Unit] = {
    val underLyingAddress = routingTable(address)
    Task
      .sequence(underLyingAddress.map { aa =>
        underLyingPeerGroup.sendMessage(aa, message)
      })
      .map(_ => ())
  }

  override def messageChannel[MessageType](implicit codec: Codec[MessageType]): Observable[(A, MessageType)] = {

    underLyingPeerGroup.messageChannel[MessageType].map {
      case (aa, messageType) => {
        val reverseLookup: mutable.Map[AA, A] = routingTable.swap
        (reverseLookup(aa), messageType)
      }
    }
  }

  override def shutdown(): Task[Unit] = underLyingPeerGroup.shutdown()

  override def initialize(): Task[Unit] = {
    routingTable += processAddress -> List(underLyingPeerGroup.processAddress)

    if (config.knownPeers.nonEmpty) {
      val (knownPeerAddress, knownPeerAddressUnderlying) = config.knownPeers.head
      routingTable += knownPeerAddress -> knownPeerAddressUnderlying

      val enrolledTask: Task[Unit] = underLyingPeerGroup
        .messageChannel[Enrolled[A, AA]]
        .map {
          case (_, enrolled) =>
            routingTable.clear()
            routingTable ++= enrolled.routingTable
            log.debug(
              s"Peer address '$processAddress' enrolled into group and installed new routing table:\n${enrolled.routingTable}"
            )
        }
        .headL

      knownPeerAddressUnderlying.foreach { aa =>
        underLyingPeerGroup
          .sendMessage(
            aa,
            EnrolMe(processAddress, config.multiCastAddresses, underLyingPeerGroup.processAddress)
          )
          .runToFuture
      }

      enrolledTask
    } else {
      Task.unit
    }
  }
}

object SimplePeerGroup {

  private[scalanet] case class EnrolMe[A, AA](myAddress: A, multiCastAddresses: List[A], myUnderlyingAddress: AA)

  private[scalanet] case class Enrolled[A, AA](address: A, underlyingAddress: AA, routingTable: List[(A, List[AA])])

  case class Config[A, AA](processAddress: A, multiCastAddresses: List[A], knownPeers: Map[A, List[AA]])
}
