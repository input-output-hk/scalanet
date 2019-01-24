package io.iohk.network.transport.tcp

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID

import io.iohk.network.MessageStream
import io.iohk.codecs.nio._
import io.iohk.network.monixstream.MonixMessageStream
import io.iohk.network.transport.NetworkTransport
import monix.execution.Cancelable
import monix.reactive.observers.Subscriber
import monix.reactive.{Observable, OverflowStrategy}

private[transport] class TcpNetworkTransport[Message](nettyTransport: NettyTransport)(implicit codec: NioCodec[Message])
    extends NetworkTransport[InetSocketAddress, Message] {

  val monixMessageStream: Observable[Message] =
    Observable.create(overflowStrategy = OverflowStrategy.Unbounded)((subscriber: Subscriber.Sync[Message]) => {

      def msgHandler(address: InetSocketAddress, message: Message): Unit = {
        subscriber.onNext(message)
      }
      val applicationId = nettyTransport.withMessageApplication(codec, msgHandler)

      cancelableMessageApplication(applicationId)
    })

  val messageStream: MessageStream[Message] = new MonixMessageStream[Message](monixMessageStream)

  override def sendMessage(address: InetSocketAddress, message: Message): Unit =
    nettyTransport.sendMessage(address, encode(message))

  private def cancelableMessageApplication(applicationId: UUID): Cancelable =
    () => nettyTransport.cancelMessageApplication(applicationId)

  private def encode(message: Message): ByteBuffer =
    codec.encode(message)
}
