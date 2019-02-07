package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.scalanet.NetUtils.randomBytes
import io.iohk.scalanet.messagestream.{CancellableFuture, MessageStream}
import io.iohk.scalanet.peergroup.SimplePeerGroup.Config
import io.iohk.scalanet.peergroup.future._
import monix.execution.CancelableFuture
import monix.execution.Scheduler.Implicits.global
import org.mockito.Mockito.when
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.mockito.MockitoSugar._

import scala.concurrent.Future

class SimplePeerGroupSpec extends FlatSpec {

  it should "send a message to a self SimplePeerGroup" in {
    val underlyingPeerGroup = mock[PeerGroup[String, Future]]
    when(underlyingPeerGroup.processAddress).thenReturn("underlying")
    val messageStream = mock[MessageStream[ByteBuffer]]
    when(underlyingPeerGroup.messageStream()).thenReturn(messageStream)
    val message = ByteBuffer.wrap(randomBytes(1024))
    val stubbedCancelableMessageF = CancellableFuture[ByteBuffer](CancelableFuture.successful(message))
    when(messageStream.head()).thenReturn(stubbedCancelableMessageF)

    val simplePeerGroup = createSimplePeerGroup(underlyingPeerGroup,Map.empty[String,String])
    val nodeAddress = "A"


    val messageReceivedF = simplePeerGroup.messageStream.head()
    simplePeerGroup.sendMessage(nodeAddress, message)


    messageReceivedF.futureValue shouldBe message
  }

  it should "send a message to a other peer in SimplePeerGroup" in {
    val underlyingPeerGroup = mock[PeerGroup[String, Future]]
    when(underlyingPeerGroup.processAddress).thenReturn("underlying")
    val messageStream = mock[MessageStream[ByteBuffer]]
    when(underlyingPeerGroup.messageStream()).thenReturn(messageStream)
    val message = ByteBuffer.wrap(randomBytes(1024))
    val stubbedCancelableMessageF = CancellableFuture[ByteBuffer](CancelableFuture.successful(message))
    when(messageStream.head()).thenReturn(stubbedCancelableMessageF)

    val nodeAddressB = "B"
    val undelyingAddressB = "underlyingB"

    val peerConfigs =  Map[String,String](nodeAddressB -> undelyingAddressB)
    val simplePeerGroup = createSimplePeerGroup(underlyingPeerGroup, peerConfigs)


    val messageReceivedF = simplePeerGroup.messageStream.head()
    simplePeerGroup.sendMessage(nodeAddressB, message)


    messageReceivedF.futureValue shouldBe message
  }


  it should "shutdown a TCPPeerGroup properly" in {
    val underlyingPeerGroup = mock[PeerGroup[String, Future]]
    val nodeAddressB = "B"
    val undelyingAddressB = "underlyingB"
    when(underlyingPeerGroup.processAddress).thenReturn("underlying")
    val peerConfigs =  Map[String,String](nodeAddressB -> undelyingAddressB)
    val simplePeerGroup = createSimplePeerGroup(underlyingPeerGroup, peerConfigs)
    when(underlyingPeerGroup.shutdown()).thenReturn(Future(()))
    simplePeerGroup.shutdown().futureValue shouldBe (())
  }

  private def createSimplePeerGroup(underLinePeerGroup: PeerGroup[String, Future],peerConfigs:Map[String,String]): SimplePeerGroup[String, Future, String] = {
    new SimplePeerGroup(Config("A",peerConfigs), underLinePeerGroup).init()
  }
}
