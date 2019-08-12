package io.iohk.scalanet.peergroup

import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.peergroup.PeerGroup.ChannelSetupException
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import org.scalatest.Matchers._
import org.scalatest.RecoverMethods.{recoverToExceptionIf, recoverToSucceededIf}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.Promise
import scala.util.Random

object StandardTestPack {

  def messageToSelfTest[A](
      alice: PeerGroup[A, String]
  )(implicit scheduler: Scheduler): Unit = {
    val message = Random.alphanumeric.take(1044).mkString
    val aliceReceived =
      alice.server().collectChannelCreated.mergeMap(_.in).headL.runToFuture
    val aliceClient: Channel[A, String] =
      alice.client(alice.processAddress).evaluated
    aliceClient.sendMessage(message).runToFuture
    aliceReceived.futureValue shouldBe message
  }

  def messagingTest[A](alice: PeerGroup[A, String], bob: PeerGroup[A, String])(
      implicit scheduler: Scheduler
  ): Unit = {
    val alicesMessage = Random.alphanumeric.take(1024).mkString
    val bobsMessage = Random.alphanumeric.take(1024).mkString

    val bobReceived = Promise[String]()

    bob.server().collectChannelCreated.foreach { channel =>
      channel.sendMessage(bobsMessage).runToFuture
      channel.in.headL.runToFuture.map(bobReceived.success)
    }

    val aliceClient = alice.client(bob.processAddress).evaluated
    val aliceReceived = aliceClient.in.headL.runToFuture
    aliceClient.sendMessage(alicesMessage).runToFuture

    bobReceived.future.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage
  }

  def serverMultiplexingTest[A](
      alice: PeerGroup[A, String],
      bob: PeerGroup[A, String]
  )(implicit scheduler: Scheduler): Unit = {
    val alicesMessage = Random.alphanumeric.take(1024).mkString
    val bobsMessage = Random.alphanumeric.take(1024).mkString

    bob
      .server()
      .collectChannelCreated
      .foreach(channel => channel.sendMessage(bobsMessage).runToFuture)

    val aliceClient1 = alice.client(bob.processAddress).evaluated
    val aliceClient2 = alice.client(bob.processAddress).evaluated

    val aliceReceived1 = aliceClient1.in.headL.runToFuture
    val aliceReceived2 = aliceClient2.in.headL.runToFuture

    aliceClient1.sendMessage(alicesMessage).runToFuture

    aliceReceived1.futureValue shouldBe bobsMessage
    recoverToSucceededIf[IllegalStateException](aliceReceived2)
  }

  def simpleMulticastTest[A](
      alice: PeerGroup[A, String],
      bob: PeerGroup[A, String],
      multicastAddress: A
  )(implicit scheduler: Scheduler): Unit = {

    val message = "News"

    bob
      .server()
      .collectChannelCreated
      .mapEval(
        channelToAlice =>
          channelToAlice.in.headL
            .flatMap(
              message => channelToAlice.sendMessage(s"Acknowledge-$message")
            )
      )
      .subscribe()

    val sendReplyTask: Task[String] = for {
      channel <- alice.client(multicastAddress)
      _ <- channel.sendMessage(message)
      reply <- channel.in.headL
    } yield reply

    sendReplyTask.evaluated shouldBe s"Acknowledge-$message"
  }

  def twoWayMulticastTest[A](
      alice: PeerGroup[A, String],
      bob: PeerGroup[A, String],
      charlie: PeerGroup[A, String],
      multicastAddress: A
  )(implicit scheduler: Scheduler, patienceConfig: ScalaFutures.PatienceConfig): Unit = {

    val message = "News"

    createServer(bob)
    createServer(charlie)

    val sendReplyTask: Task[Set[String]] = for {
      channel <- alice.client(multicastAddress)
      _ <- channel.sendMessage(message)
      replies <- channel.in.dump("REPLIES").take(2).toListL
    } yield replies.toSet

    sendReplyTask.evaluated shouldBe Set(
      s"Bob-Acknowledge-$message",
      s"Charlie-Acknowledge-$message"
    )
  }

  private def createServer[A](pg: PeerGroup[A, String])(implicit scheduler: Scheduler): Cancelable = {
    pg.server()
      .collectChannelCreated
      .mapEval(
        channelToAlice =>
          channelToAlice.in.headL
            .flatMap { message =>
              println(s"I am ${pg.processAddress} acknowledging message $message")
              channelToAlice.sendMessage(s"${pg.processAddress}-Acknowledge-$message")
            }
      )
      .subscribe()
  }

  def shouldErrorForMessagingAnInvalidAddress[A](
      alice: PeerGroup[A, String],
      invalidAddress: A
  )(implicit scheduler: Scheduler): Unit = {

    val aliceError =
      recoverToExceptionIf[ChannelSetupException[InetMultiAddress]] {
        alice.client(invalidAddress).runToFuture
      }

    aliceError.futureValue.to shouldBe invalidAddress
  }
}
