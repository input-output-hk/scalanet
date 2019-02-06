package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.scalanet.NetUtils.randomBytes
import io.iohk.scalanet.peergroup.SimplePeerGroup.Config
import org.scalatest.FlatSpec
import io.iohk.scalanet.peergroup.future._
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.Future
import org.scalatest.mockito.MockitoSugar._
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.Matchers._

class SimplePeerGroupSpec extends FlatSpec {

  it should "send a message to a self SimplePeerGroup" in {
    val underlyingPeerGroup = mock[PeerGroup[String, Future]]
    when(underlyingPeerGroup.processAddress).thenReturn("underlying")
    val simplePeerGroup = createSimplePeerGroup(underlyingPeerGroup)
    val nodeAddress = "A"

    val message = ByteBuffer.wrap(randomBytes(1024))
    val messageReceivedF = simplePeerGroup.messageStream().head()
    simplePeerGroup.sendMessage(nodeAddress, message)

    messageReceivedF.futureValue shouldBe message
  }

  private def createSimplePeerGroup(underLinePeerGroup: PeerGroup[String, Future]): SimplePeerGroup[String, Future, String] = {
    new SimplePeerGroup(Config("A"), underLinePeerGroup)
  }
}
