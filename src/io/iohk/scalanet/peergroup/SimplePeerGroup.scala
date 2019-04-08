//package io.iohk.scalanet.peergroup
//
//import java.util.concurrent.ConcurrentHashMap
//
//import io.iohk.scalanet.peergroup.PeerGroup.NonTerminalPeerGroup
//import io.iohk.scalanet.peergroup.SimplePeerGroup.Config
//
//import scala.collection.mutable
//import scala.collection.JavaConverters._
//import io.iohk.decco.auto._
//import io.iohk.decco._
//import SimplePeerGroup._
//import monix.eval.Task
//import monix.execution.Scheduler
//import monix.reactive.Observable
//import org.slf4j.LoggerFactory
//
//class SimplePeerGroup[A, AA](
//    val config: Config[A, AA],
//    underLyingPeerGroup: PeerGroup[AA]
//)(
//    implicit aCodec: Codec[A],
//    aaCodec: Codec[AA],
//    scheduler: Scheduler
//) extends NonTerminalPeerGroup[A, AA](underLyingPeerGroup) {
//
//  private val log = LoggerFactory.getLogger(getClass)
//
//  private val routingTable: mutable.Map[A, AA] = new ConcurrentHashMap[A, AA]().asScala
//  private val multiCastTable: mutable.Map[A, List[AA]] = new ConcurrentHashMap[A, List[AA]]().asScala
//
//  private implicit val apc: PartialCodec[A] = aCodec.partialCodec
//  private implicit val aapc: PartialCodec[AA] = aaCodec.partialCodec
//
//  override val processAddress: A = config.processAddress
//
//  underLyingPeerGroup
//    .messageChannel[EnrolMe[A, AA]]
//    .foreach {
//      case (_, enrolMe) =>
//        routingTable += enrolMe.myAddress -> enrolMe.myUnderlyingAddress
//        updateMulticastTable(enrolMe)
//        notifyPeer(enrolMe)
//    }
//
//  private def updateMulticastTable(enrolMe: EnrolMe[A, AA]): Unit = {
//    enrolMe.multicastAddresses foreach { a =>
//      val existingAddress: List[AA] = if (multiCastTable.contains(a)) {
//        multiCastTable(a)
//      } else Nil
//      multiCastTable += a -> (existingAddress ::: List(enrolMe.myUnderlyingAddress))
//    }
//  }
//
//  private def notifyPeer(enrolMe: EnrolMe[A, AA]) = {
//    underLyingPeerGroup
//      .sendMessage(
//        enrolMe.myUnderlyingAddress,
//        Enrolled(enrolMe.myAddress, enrolMe.myUnderlyingAddress, routingTable.toMap, multiCastTable.toMap)
//      )
//      .runToFuture
//    log.debug(
//      s"Processed enrolment message $enrolMe at address '$processAddress' with corresponding routing table update."
//    )
//  }
//
//  override def sendMessage[MessageType: Codec](address: A, message: MessageType): Task[Unit] = {
//
//    val underLyingAddress = if (routingTable.contains(address)) List(routingTable(address)) else multiCastTable(address)
//    Task
//      .sequence(underLyingAddress.map { aa =>
//        underLyingPeerGroup.sendMessage(aa, message)
//      })
//      .map(_ => ())
//  }
//
//  override def messageChannel[MessageType](implicit codec: Codec[MessageType]): Observable[(A, MessageType)] = {
//
//    underLyingPeerGroup.messageChannel[MessageType].map {
//      case (aa, messageType) => {
//        val reverseLookup: mutable.Map[AA, A] = routingTable.map(_.swap)
//        (reverseLookup(aa), messageType)
//      }
//    }
//  }
//
//  override def shutdown(): Task[Unit] = underLyingPeerGroup.shutdown()
//
//  override def initialize(): Task[Unit] = {
//    routingTable += processAddress -> underLyingPeerGroup.processAddress
//
//    if (config.knownPeers.nonEmpty) {
//      val (knownPeerAddress, knownPeerAddressUnderlying) = config.knownPeers.head
//      routingTable += knownPeerAddress -> knownPeerAddressUnderlying
//
//      val enrolledTask: Task[Unit] = underLyingPeerGroup
//        .messageChannel[Enrolled[A, AA]]
//        .map {
//          case (_, enrolled) =>
//            routingTable.clear()
//            routingTable ++= enrolled.routingTable
//            log.debug(
//              s"Peer address '$processAddress' enrolled into group and installed new routing table:\n${enrolled.routingTable}"
//            )
//        }
//        .headL
//
//      underLyingPeerGroup
//        .sendMessage(
//          knownPeerAddressUnderlying,
//          EnrolMe(processAddress, config.multicastAddresses, underLyingPeerGroup.processAddress)
//        )
//        .runToFuture
//
//      enrolledTask
//    } else {
//      Task.unit
//    }
//  }
//}
//
//object SimplePeerGroup {
//
//  private[scalanet] case class EnrolMe[A, AA](myAddress: A, multicastAddresses: List[A], myUnderlyingAddress: AA)
//
//  private[scalanet] case class Enrolled[A, AA](
//      address: A,
//      underlyingAddress: AA,
//      routingTable: Map[A, AA],
//      multiCastTable: Map[A, List[AA]]
//  )
//
//  case class Config[A, AA](processAddress: A, multicastAddresses: List[A], knownPeers: Map[A, AA])
//}
