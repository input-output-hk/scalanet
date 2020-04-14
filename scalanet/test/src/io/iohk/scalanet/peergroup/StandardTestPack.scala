package io.iohk.scalanet.peergroup

import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.peergroup.Channel.MessageReceived
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

    bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runToFuture)
    val aliceClient = alice.client(bob.processAddress).evaluated
    val aliceReceived = aliceClient.in.collect { case MessageReceived(m) => m }.headL.runToFuture

    aliceClient.sendMessage(alicesMessage).runToFuture
    val bobReceived: Future[String] =
      bob
        .server()
        .collectChannelCreated
        .mergeMap(channel => channel.in)
        .collect { case MessageReceived(m) => m }
        .headL
        .runToFuture
    aliceClient.in.connect()
    bob.server().collectChannelCreated.foreach(_.in.connect())
    bob.server().connect()

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

    val aliceReceived1 = aliceClient1.in.collect { case MessageReceived(m) => m }.headL.runToFuture
    val aliceReceived2 = aliceClient2.in.collect { case MessageReceived(m) => m }.headL.runToFuture
    aliceClient1.sendMessage(alicesMessage).runToFuture
    aliceClient2.sendMessage(alicesMessage).runToFuture

    aliceClient1.in.connect()
    aliceClient2.in.connect()
    bob.server().collectChannelCreated.foreach(channel => channel.in.connect())
    bob.server().connect()
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
