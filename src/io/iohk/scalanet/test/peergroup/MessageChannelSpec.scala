package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec.heapCodec
import io.iohk.decco.PartialCodec
import org.scalatest.FlatSpec
import org.mockito.Mockito.{ when}
import org.scalatest.mockito.MockitoSugar._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.Matchers._

import scala.concurrent.Future
import io.iohk.decco.auto._
import io.iohk.scalanet.messagestream.MonixMessageStream
class MessageChannelSpec extends FlatSpec{

  behavior of "MessageChannel"


//  it should  "able to send a typed message" in {
//    val message = "String"
//    val address = "bob"
//    val codec = heapCodec[String]
//    val bytes: ByteBuffer = codec.encode(message)
//
//    val peerGroup = mock[PeerGroup[String,Future]]
//    val decoderTable = new DecoderTable()
//
//    when(peerGroup.messageStream()).thenReturn(MonixMessageStream.empty[ByteBuffer])
//    val messageChannel = new MessageChannel[String,String,Future](peerGroup,decoderTable)
//    messageChannel.sendMessage(address,message)
//
//    verify(peerGroup).sendMessage(address,bytes)
//
//  }



  it should  "able to consume message stream" in {
    val message = "String"
    val address = "bob"
    val codec = heapCodec[String]
    val bytes: ByteBuffer = codec.encode(message)
    val pc = PartialCodec[String]

    val peerGroup = mock[PeerGroup[String,Future]]
    val decoderTable = new DecoderTable()
    //val function = mock[(Int,ByteBuffer) => Unit]
    //decoderTable.put(pc.typeCode,function)

    when(peerGroup.messageStream()).thenReturn(MonixMessageStream.fromIterable(List(bytes)))
    val messageChannel = new MessageChannel[String,String,Future](peerGroup,decoderTable)
      Thread.sleep(2000)
    val receivedMessageF=  messageChannel.inboundMessages.head().futureValue
    println("********" + receivedMessageF)
    receivedMessageF shouldBe message
  }


}
