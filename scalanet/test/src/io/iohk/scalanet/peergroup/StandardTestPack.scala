package io.iohk.scalanet.peergroup

import io.iohk.scalanet.peergroup.Channel.MessageReceived
import io.iohk.scalanet.peergroup.PeerGroup.ChannelSetupException
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
import monix.execution.Scheduler
import monix.eval.Task
import org.scalatest.Matchers._
import org.scalatest.RecoverMethods.{recoverToExceptionIf}
import org.scalatest.concurrent.ScalaFutures._

import scala.util.Random

object StandardTestPack {

  // Test that Alice can send a message to Bob and receive an answer.
  def messagingTest[A](alice: PeerGroup[A, String], bob: PeerGroup[A, String])(
      implicit scheduler: Scheduler
  ): Task[Unit] = {
    val alicesMessage = Random.alphanumeric.take(1024).mkString
    val bobsMessage = Random.alphanumeric.take(1024).mkString

    for {
      bobReceiver <- bob.server.collectChannelCreated
        .mapEval {
          case (channel, release) =>
            channel.sendMessage(bobsMessage) >>
              channel.in
                .collect {
                  case MessageReceived(m) => m
                }
                .headL
                .guarantee(release)
        }
        .headL
        .start

      aliceClient <- alice.client(bob.processAddress).allocated
      aliceReceiver <- aliceClient._1.in
        .collect {
          case MessageReceived(m) => m
        }
        .headL
        .start

      _ <- aliceClient._1.sendMessage(alicesMessage)

      _ = aliceClient._1.in.connect()
      _ = bob.server.collectChannelCreated.foreach(_._1.in.connect())
      _ = bob.server.connect()

      bobReceived <- bobReceiver.join
      aliceReceived <- aliceReceiver.join
      _ <- aliceClient._2
    } yield {
      bobReceived shouldBe alicesMessage
      aliceReceived shouldBe bobsMessage
    }
  }

  // Test that Alice can send messages to Bob concurrently and receive answers on both channels.
  def serverMultiplexingTest[A](alice: PeerGroup[A, String], bob: PeerGroup[A, String])(
      implicit scheduler: Scheduler
  ): Task[Unit] = {
    val alicesMessage = Random.alphanumeric.take(1024).mkString
    val bobsMessage = Random.alphanumeric.take(1024).mkString

    for {
      _ <- bob.server.collectChannelCreated.foreachL {
        case (channel, release) => channel.sendMessage(bobsMessage).guarantee(release).runAsyncAndForget
      }.startAndForget

      aliceClient1 <- alice.client(bob.processAddress).allocated
      aliceClient2 <- alice.client(bob.processAddress).allocated

      aliceReceiver1 <- aliceClient1._1.in.collect { case MessageReceived(m) => m }.headL.start
      aliceReceiver2 <- aliceClient2._1.in.collect { case MessageReceived(m) => m }.headL.start
      _ <- aliceClient1._1.sendMessage(alicesMessage)
      _ <- aliceClient2._1.sendMessage(alicesMessage)

      _ = aliceClient1._1.in.connect()
      _ = aliceClient2._1.in.connect()
      _ = bob.server.collectChannelCreated.foreach(channel => channel._1.in.connect())
      _ = bob.server.connect()

      aliceReceived1 <- aliceReceiver1.join
      aliceReceived2 <- aliceReceiver2.join
      _ <- aliceClient1._2
      _ <- aliceClient2._2
    } yield {
      aliceReceived1 shouldBe bobsMessage
      aliceReceived2 shouldBe bobsMessage
    }
  }

  def shouldErrorForMessagingAnInvalidAddress[A](alice: PeerGroup[A, String], invalidAddress: A)(
      implicit scheduler: Scheduler
  ): Unit = {

    val aliceError = recoverToExceptionIf[ChannelSetupException[InetMultiAddress]] {
      alice.client(invalidAddress).use(_ => Task.unit).runToFuture
    }

    aliceError.futureValue.to shouldBe invalidAddress
  }
}
