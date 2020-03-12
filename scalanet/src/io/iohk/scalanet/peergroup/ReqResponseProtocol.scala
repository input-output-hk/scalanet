package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID

import cats.effect.concurrent.Ref
import io.iohk.scalanet.codec.FramingCodec
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.peergroup.dynamictls.{DynamicTLSPeerGroup, Secp256k1}
import io.iohk.scalanet.peergroup.InetPeerGroupUtils.ChannelId
import io.iohk.scalanet.peergroup.ReqResponseProtocol._
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.PeerInfo
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.observables.ConnectableObservable
import scodec.Codec

import scala.concurrent.duration.{FiniteDuration, _}

/**
  * Simple higher level protocol on top of generic peer group. User is shielded from differnt implementation details like:
  * channels, observables etc.
  *
  * For now used only in testing as:
  *   - it lacks any error handling
  *   - it is not entairly thread safe
  *   - can only handle simple server handler
  *   - there is no resource cleaning
  *
  * @param group transport peer group
  * @param state currently open client channels
  * @tparam A used addressing scheme
  * @tparam M the message type.
  */
class ReqResponseProtocol[A, M](
    group: PeerGroup[A, MessageEnvelope[M]],
    state: Ref[Task, Map[ChannelId, Channel[A, MessageEnvelope[M]]]]
)(implicit s: Scheduler, a: Addressable[A]) {

  private def getChan(
      to: A,
      chId: ChannelId,
      st: Map[ChannelId, Channel[A, MessageEnvelope[M]]]
  ): Task[Channel[A, MessageEnvelope[M]]] = {
    // this is not really thread safe.
    if (st.contains(chId)) {
      Task.now(st(chId))
    } else {
      for {
        newCh <- group.client(to)
        // start publishing incoming messages to any subscriber
        // in normal circumstances we should keep cancellation token to clear up resources
        canc = newCh.in.connect()
        // Keep in mind that stream is back pressured for all subscribers so in case of many parallel requests to one client
        // waiting for response on first request can influence result of second request
        _ <- state.set(st.updated(chId, newCh))
      } yield newCh
    }
  }

  // It do not closes client channel after each message as, in case of tcp it would be really costly
  // to create new tcp connection for each message.
  // it probably should return Task[Either[E, M]]
  def send(m: M, to: A, requestDuration: FiniteDuration = 5.seconds): Task[M] = {
    val chID = (a.getAddress(group.processAddress), a.getAddress(to))
    for {
      st <- state.get
      ch <- getChan(to, chID, st)
      randomUuid = UUID.randomUUID()
      mes = MessageEnvelope(randomUuid, m)
      resp <- sendMandAwaitForResponse(ch, mes, requestDuration)
    } yield resp
  }

  private def sendMandAwaitForResponse(
      c: Channel[A, MessageEnvelope[M]],
      messageToSend: MessageEnvelope[M],
      timeOutDuration: FiniteDuration
  ): Task[M] =
    for {
      // sending and subsription are done in parallel to not miss response message by chance
      // alse in case of send failure, any resource will be cleaned
      result <- Task
        .parMap2(c.sendMessage(messageToSend), subscribeForResponse(c.in, messageToSend.id))(
          (_, response) => response.m
        )
        .timeout(timeOutDuration)
    } yield result

  private def subscribeForResponse(source: ConnectableObservable[MessageEnvelope[M]], responseId: UUID) = {
    source.collect {
      case response if response.id == responseId => response
    }.headL
  }

  def startHandling(requestHandler: M => M) = {
    group
      .server()
      .refCount
      .collectChannelCreated
      .mergeMap(channel => channel.in.refCount.map(request => (channel, request)))
      .foreachL {
        case (ch, mes) =>
          ch.sendMessage(MessageEnvelope(mes.id, requestHandler(mes.m))).runAsyncAndForget
      }
  }

  def processAddress: A = group.processAddress
}

object ReqResponseProtocol {
  import scodec.codecs.implicits._

  // Default scodec product codec deriviation due to implicits
  final case class MessageEnvelope[M](id: UUID, m: M)

  private def buildProtocol[A, M](
      group: PeerGroup[A, MessageEnvelope[M]]
  )(implicit s: Scheduler, a: Addressable[A]): Task[ReqResponseProtocol[A, M]] = {
    for {
      _ <- group.initialize()
      initState <- Ref.of[Task, Map[ChannelId, Channel[A, MessageEnvelope[M]]]](Map.empty)
      prot <- Task.now(new ReqResponseProtocol[A, M](group, initState))
    } yield prot
  }

  sealed abstract class TransportProtocol extends Product with Serializable {
    type AddressingType
    def getProtocol[M](
        address: InetSocketAddress
    )(implicit s: Scheduler, c: Codec[M]): Task[ReqResponseProtocol[AddressingType, M]]
  }
  case object Udp extends TransportProtocol {
    override type AddressingType = InetMultiAddress

    override def getProtocol[M](
        address: InetSocketAddress
    )(implicit s: Scheduler, c: Codec[M]): Task[ReqResponseProtocol[InetMultiAddress, M]] = {
      getUdpReqResponseProtocolClient(address)
    }
  }
  case object Tcp extends TransportProtocol {
    override type AddressingType = InetMultiAddress

    override def getProtocol[M](
        address: InetSocketAddress
    )(implicit s: Scheduler, c: Codec[M]): Task[ReqResponseProtocol[InetMultiAddress, M]] = {
      getTcpReqResponseProtocolClient(address)
    }
  }
  case object DynamicTLS extends TransportProtocol {
    override type AddressingType = PeerInfo

    override def getProtocol[M](
        address: InetSocketAddress
    )(implicit s: Scheduler, c: Codec[M]): Task[ReqResponseProtocol[PeerInfo, M]] = {
      getTlsReqResponseProtocolClient(address)
    }
  }

  def getTcpReqResponseProtocolClient[M](
      address: InetSocketAddress
  )(implicit s: Scheduler, c: Codec[M]): Task[ReqResponseProtocol[InetMultiAddress, M]] = {
    val codec = implicitly[Codec[MessageEnvelope[M]]]
    implicit lazy val framingCodec = new FramingCodec[MessageEnvelope[M]](codec)
    val pg1 = new TCPPeerGroup[MessageEnvelope[M]](TCPPeerGroup.Config(address))
    buildProtocol(pg1)
  }

  def getTlsReqResponseProtocolClient[M](
      address: InetSocketAddress
  )(implicit s: Scheduler, c: Codec[M]): Task[ReqResponseProtocol[PeerInfo, M]] = {
    val codec = implicitly[Codec[MessageEnvelope[M]]]
    implicit lazy val framingCodec = new FramingCodec[MessageEnvelope[M]](codec)
    val rnd = new SecureRandom()
    val hostkeyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)

    for {
      config <- Task.fromTry(DynamicTLSPeerGroup.Config(address, Secp256k1, hostkeyPair, rnd))
      pg = new DynamicTLSPeerGroup[MessageEnvelope[M]](config)
      prot <- buildProtocol(pg)
    } yield prot
  }

  def getUdpReqResponseProtocolClient[M](
      address: InetSocketAddress
  )(implicit s: Scheduler, c: Codec[M]): Task[ReqResponseProtocol[InetMultiAddress, M]] = {
    val pg1 = new UDPPeerGroup[MessageEnvelope[M]](UDPPeerGroup.Config(address))
    buildProtocol(pg1)
  }

}
