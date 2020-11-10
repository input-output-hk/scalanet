package io.iohk.scalanet.peergroup

import cats.implicits._
import io.iohk.scalanet.peergroup.implicits._
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
    val alicesMessage = "Hi Bob!"
    val bobsMessage = "Hi Alice!"

    (for {
      bobReceiver <- bob.serverEventObservable.collectChannelCreated
        .mergeMap {
          case (channel, release) =>
            channel.channelEventObservable
              .collect {
                case MessageReceived(m) => m
              }
              .take(1)
              .mapEval { msg =>
                channel.sendMessage(bobsMessage).as(msg)
              }
              .guarantee(release)
        }
        .headL
        .start

      aliceClient <- alice.client(bob.processAddress).allocated

      aliceReceiver <- aliceClient._1.channelEventObservable
        .collect {
          case MessageReceived(m) => m
        }
        .headL
        .start

      _ <- aliceClient._1.sendMessage(alicesMessage)

      bobReceived <- bobReceiver.join
      aliceReceived <- aliceReceiver.join
      _ <- aliceClient._2
    } yield {
      bobReceived shouldBe alicesMessage
      aliceReceived shouldBe bobsMessage
    }).void
  }

  // Same as messagingTest but without using the ConnectableObservables.
  def messagingTestNext[A](alice: PeerGroup[A, String], bob: PeerGroup[A, String])(
      implicit scheduler: Scheduler
  ): Task[Unit] = {
    val alicesMessage = "Hi Bob!"
    val bobsMessage = "Hi Alice!"

    def sendAndReceive(msgOut: String, channel: Channel[A, String]): Task[String] = {
      channel.sendMessage(msgOut) >>
        // In this test we know there shouldn't be any other message arriving; in practice we'd use an Iterant.
        channel.nextChannelEvent
          .flatMap {
            case Some(MessageReceived(msgIn)) => Task.pure(msgIn)
            case other => Task.raiseError(new RuntimeException(s"Unexpected channel event: $other"))
          }
    }

    (for {
      // In this test we know there won't be any other incoming connection; in practice we'd spawn a Fiber.
      bobReceiver <- bob.nextServerEvent.flatMap {
        case Some(ChannelCreated(channel, release)) =>
          sendAndReceive(bobsMessage, channel).guarantee(release)
        case other =>
          Task.raiseError(new RuntimeException(s"Unexpected server event: $other"))
      }.start

      aliceReceived <- alice.client(bob.processAddress).use { channel =>
        sendAndReceive(alicesMessage, channel)
      }

      bobReceived <- bobReceiver.join
    } yield {
      bobReceived shouldBe alicesMessage
      aliceReceived shouldBe bobsMessage
    }).void
  }

  // Test that Alice can send messages to Bob concurrently and receive answers on both channels.
  def serverMultiplexingTest[A](alice: PeerGroup[A, String], bob: PeerGroup[A, String])(
      implicit scheduler: Scheduler
  ): Task[Unit] = {
    val alicesMessage = Random.alphanumeric.take(1024).mkString
    val bobsMessage = Random.alphanumeric.take(1024).mkString

    (for {
      _ <- bob.serverEventObservable.collectChannelCreated.foreachL {
        case (channel, release) => channel.sendMessage(bobsMessage).guarantee(release).runAsyncAndForget
      }.startAndForget

      aliceClient1 <- alice.client(bob.processAddress).allocated
      aliceClient2 <- alice.client(bob.processAddress).allocated

      aliceReceiver1 <- aliceClient1._1.channelEventObservable.collect { case MessageReceived(m) => m }.headL.start
      aliceReceiver2 <- aliceClient2._1.channelEventObservable.collect { case MessageReceived(m) => m }.headL.start
      _ <- aliceClient1._1.sendMessage(alicesMessage)
      _ <- aliceClient2._1.sendMessage(alicesMessage)

      aliceReceived1 <- aliceReceiver1.join
      aliceReceived2 <- aliceReceiver2.join
      _ <- aliceClient1._2
      _ <- aliceClient2._2
    } yield {
      aliceReceived1 shouldBe bobsMessage
      aliceReceived2 shouldBe bobsMessage
    }).void
  }

  def shouldErrorForMessagingAnInvalidAddress[A](alice: PeerGroup[A, String], invalidAddress: A)(
      implicit scheduler: Scheduler
  ): Unit = {

    val aliceError = recoverToExceptionIf[ChannelSetupException[InetMultiAddress]] {
      alice.client(invalidAddress).use(_ => Task.unit).runToFuture
    }

    aliceError.futureValue.to shouldBe invalidAddress
    ()
  }
}
