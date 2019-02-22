package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec.heapCodec
import org.scalatest.FlatSpec
import org.mockito.Mockito.{when, verify}
import org.scalatest.mockito.MockitoSugar._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.Matchers._

import scala.concurrent.Future
import io.iohk.decco.auto._
import io.iohk.scalanet.messagestream.MonixMessageStream
import org.scalatest.concurrent.ScalaFutures
class MessageChannelSpec extends FlatSpec {

  behavior of "MessageChannel"

  it should "able to send a typed message" in {
    val message = "String"
    val address = "bob"
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode(message)

    val peerGroup = mock[PeerGroup[String, Future]]
    val decoderTable = new DecoderTable()

    when(peerGroup.messageStream()).thenReturn(MonixMessageStream.empty[ByteBuffer])
    val messageChannel = new MessageChannel[String, String, Future](peerGroup, decoderTable)
    messageChannel.sendMessage(address, message)

    verify(peerGroup).sendMessage(address, bytes)

  }

  import scala.concurrent.duration._
  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 1 second, interval = 100 millis)
  it should "able to consume message stream" in {
    val message = "String"
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode(message)
    val peerGroup = mock[PeerGroup[String, Future]]
    val subscribers = new Subscribers[ByteBuffer]()

    when(peerGroup.messageStream()).thenReturn(subscribers.messageStream)
    when(peerGroup.processAddress).thenReturn("A")

    val messageChannel = new MessageChannel[String, String, Future](peerGroup, new DecoderTable())
    val receivedMessageF = messageChannel.inboundMessages.head()

    subscribers.notify(bytes)

    receivedMessageF.futureValue shouldBe message
  }

}
