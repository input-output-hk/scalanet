package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.decco.Codec.heapCodec
import org.scalatest.FlatSpec
import org.mockito.Mockito.{verify, when}
import org.scalatest.mockito.MockitoSugar._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.Matchers._

import scala.concurrent.Future
import io.iohk.decco.auto._
import io.iohk.scalanet.messagestream.MonixMessageStream
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class MessageChannelSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 1 second, interval = 100 millis)

  behavior of "MessageChannel"

  it should "able to send a typed message" in {
    val message = "Hello"
    val address = "bob"
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode(message)
    val peerGroup = mock[PeerGroup[String, Future]]

    when(peerGroup.messageStream()).thenReturn(MonixMessageStream.empty[ByteBuffer])
    val messageChannel = new MessageChannel[String, String, Future](peerGroup)
    messageChannel.sendMessage(address, message)

    verify(peerGroup).sendMessage(address, bytes)
  }

  it should "add a message from a peer group to its message stream" in {
    val message = "Hello"
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode(message)
    val headerWidth = 20
    val peerGroup = mock[PeerGroup[String, Future]]
    val messageChannel = new MessageChannel[String, String, Future](peerGroup)
    val decoderTable = new DecoderTable()
    decoderTable.put(codec.typeCode.id, messageChannel.handleMessage)

    Codec.decodeFrame(decoderTable.entries, 0, bytes)
    val messageF = messageChannel.inboundMessages.head()
    messageChannel.handleMessage(headerWidth, bytes)

    messageF.futureValue shouldBe message
  }
}
