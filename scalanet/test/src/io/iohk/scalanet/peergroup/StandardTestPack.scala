package io.iohk.scalanet.peergroup

import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.PeerGroup.ChannelSetupException
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import monix.execution.Scheduler
import org.scalatest.Matchers._
import org.scalatest.RecoverMethods.{recoverToExceptionIf, recoverToSucceededIf}
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.Future
import scala.util.Random

object StandardTestPack {

  def messagingTest[A](alice: PeerGroup[A, String], bob: PeerGroup[A, String])(implicit scheduler: Scheduler): Unit = {
    val alicesMessage = Random.alphanumeric.take(1024).mkString
    val bobsMessage = Random.alphanumeric.take(1024).mkString

    bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runAsync)
    val aliceClient = alice.client(bob.processAddress).evaluated
    val aliceReceived = aliceClient.in.headL.runAsync

    aliceClient.sendMessage(alicesMessage).runAsync
    val bobReceived: Future[String] =
      bob.server().collectChannelCreated.mergeMap(channel => channel.in).headL.runAsync
    aliceClient.in.connect()
    bob.server().collectChannelCreated.foreach(_.in.connect())
    bob.server().asInstanceOf[ConnectableSubject[String]].connect()

    bobReceived.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage
  }

  def serverMultiplexingTest[A](alice: PeerGroup[A, String], bob: PeerGroup[A, String])(
      implicit scheduler: Scheduler
  ): Unit = {
    val alicesMessage = Random.alphanumeric.take(1024).mkString
    val bobsMessage = Random.alphanumeric.take(1024).mkString

    bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runAsync)

    val aliceClient1 = alice.client(bob.processAddress).evaluated
    val aliceClient2 = alice.client(bob.processAddress).evaluated

    val aliceReceived1 = aliceClient1.in.headL.runAsync
    val aliceReceived2 = aliceClient2.in.headL.runAsync

    aliceClient1.sendMessage(alicesMessage).runAsync
    aliceClient1.in.connect()
    aliceClient2.in.connect()
    bob.server().collectChannelCreated.foreach(channel => channel.in.connect())
    bob.server().asInstanceOf[ConnectableSubject[String]].connect()
    aliceReceived1.futureValue shouldBe bobsMessage
    recoverToSucceededIf[IllegalStateException](aliceReceived2)
  }

  def shouldErrorForMessagingAnInvalidAddress[A](alice: PeerGroup[A, String], invalidAddress: A)(
      implicit scheduler: Scheduler
  ): Unit = {

    val aliceError = recoverToExceptionIf[ChannelSetupException[InetMultiAddress]] {
      alice.client(invalidAddress).runAsync
    }

    aliceError.futureValue.to shouldBe invalidAddress
  }
}
