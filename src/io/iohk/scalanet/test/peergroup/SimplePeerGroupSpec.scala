package io.iohk.scalanet.test.peergroup

import io.iohk.scalanet.peergroup.SimplePeerGroup.Config
import io.iohk.scalanet.peergroup.{PeerGroup, SimplePeerGroup}
import org.scalatest.FlatSpec
import io.iohk.scalanet.peergroup.future._
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.Future
import org.scalatest.mockito.MockitoSugar._
//import org.scalatest.Matchers._

class SimplePeerGroupSpec extends FlatSpec {

  it should "send a message to a self SimplePeerGroup" in {

    val mockUnderLinePeerGroup = mock[PeerGroup[String, Future]]

    val simplePeerGroup = createSimplePeerGroup(mockUnderLinePeerGroup)

  }

  private def createSimplePeerGroup(underLinePeerGroup: PeerGroup[String, Future]) = {
    val config: Config = Config()
    new SimplePeerGroup(config, underLinePeerGroup)
  }
//
//  private def withTwoRandomSPeerGroups(testCode: (TCPPeerGroup[Future], TCPPeerGroup[Future]) => Any): Unit = {
//    val pg1 = randomTCPPeerGroup()
//    val pg2 = randomTCPPeerGroup()
//    try {
//      testCode(pg1, pg2)
//    } finally {
//      pg1.shutdown()
//      pg2.shutdown()
//    }
//  }

}
