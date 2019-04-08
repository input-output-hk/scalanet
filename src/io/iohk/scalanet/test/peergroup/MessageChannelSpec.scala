package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.decco.Codec.heapCodec
import io.iohk.decco.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.mockito.MockitoSugar._

import scala.concurrent.duration._

class MessageChannelSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 1 second, interval = 100 millis)

  behavior of "MessageChannel"

  it should "add a message from a peer group to its message stream" in {
    val address = "Alice"
    val message = "Hello"
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode(message)
    val headerWidth = 20
    val peerGroup = mock[PeerGroup[String, String]]
    val messageChannel = new MessageChannel[String, String](peerGroup)
    val decoderTable = new DecoderTable[String]()
    decoderTable.put(codec.typeCode.id, messageChannel.handleMessage)

    Codec.decodeFrame(decoderTable.entries(address), 0, bytes)
    val messageF = messageChannel.inboundMessages.headL.runToFuture
    messageChannel.handleMessage(address)(headerWidth, bytes)

    messageF.futureValue shouldBe (address, message)
  }
}
