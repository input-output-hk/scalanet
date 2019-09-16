package io.iohk.scalanet.peergroup

import scala.concurrent.Future
import scala.util.Random
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.peergroup.PeerGroup.ChannelSetupException
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import monix.execution.Scheduler
import org.scalatest.Matchers._
import org.scalatest.RecoverMethods.{recoverToExceptionIf, recoverToSucceededIf}

object StandardTestPack {

  def messagingTest[A](alice: PeerGroup[A, String], bob: PeerGroup[A, String])(implicit scheduler: Scheduler): Unit = {
    val alicesMessage = Random.alphanumeric.take(1024).mkString
    val bobsMessage = Random.alphanumeric.take(1024).mkString

    val bobReceived: Future[String] =
      bob.server().collectChannelCreated.mergeMap(channel => channel.in).headL.runToFuture
    bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runToFuture)

    val aliceClient = alice.client(bob.processAddress).evaluated
    val aliceReceived = aliceClient.in.headL.runToFuture
    aliceClient.sendMessage(alicesMessage).runToFuture

    bobReceived.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage
  }

  def serverMultiplexingTest[A](alice: PeerGroup[A, String], bob: PeerGroup[A, String])(
      implicit scheduler: Scheduler
  ): Unit = {
    val alicesMessage = Random.alphanumeric.take(1024).mkString
    val bobsMessage = Random.alphanumeric.take(1024).mkString
    bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runToFuture)

    val aliceClient1 = alice.client(bob.processAddress).evaluated
    val aliceClient2 = alice.client(bob.processAddress).evaluated

    val aliceReceived1 = aliceClient1.in.headL.runToFuture
    val aliceReceived2 = aliceClient2.in.headL.runToFuture

    aliceClient1.sendMessage(alicesMessage).runToFuture
    aliceClient2.sendMessage(alicesMessage).runToFuture

    aliceReceived1.futureValue shouldBe bobsMessage
    aliceReceived2.futureValue shouldBe bobsMessage

    recoverToSucceededIf[IllegalStateException](aliceReceived2)
  }

  def shouldErrorForMessagingAnInvalidAddress[A](alice: PeerGroup[A, String], invalidAddress: A)(
      implicit scheduler: Scheduler
  ): Unit = {

    val aliceError = recoverToExceptionIf[ChannelSetupException[InetMultiAddress]] {
      alice.client(invalidAddress).runToFuture
    }

    aliceError.futureValue.to shouldBe invalidAddress
  }
}
