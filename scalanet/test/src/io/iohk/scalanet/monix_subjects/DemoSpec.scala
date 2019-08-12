package io.iohk.scalanet.monix_subjects

import monix.execution.CancelableFuture
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._

class DemoSpec extends FlatSpec {
  import monix.execution.Scheduler.Implicits.global

  behavior of "MyOldPeerGroup"

  it should "lose messages" in {
    val pg = new MyOldPeerGroup()

    allProcessedMessages(pg).futureValue shouldBe List()
  }

  it should "allow multiple subscriptions to the server Observable" in {
    val pg = new MyOldPeerGroup()

    allProcessedMessages(pg).futureValue
    allProcessedMessages(pg).futureValue
  }

  behavior of "MyConnectPeerGroup"

  it should "not lose messages" in {
    val pg = new MyConnectPeerGroup()

    pg.server.foreach { channel =>
      println(s"new channel ${channel.id}")
      channel.in.foreach(println)
      channel.in.connect()
    }
    pg.server.connect()
  }

  it should "lose messages for multiple subscribers" in {
    val pg = new MyConnectPeerGroup()

    pg.server.foreach { channel =>
      println(s"new channel ${channel.id}")
      channel.in.foreach(println)
      channel.in.connect()
    }

    pg.server.foreach { channel =>
      println(s"new channel ${channel.id}")
      channel.in.foreach(println)
      channel.in.connect()
    }
    pg.server.connect()
  }

  behavior of "MyNewPeerGroup"

  it should "not lose messages" in {
    val pg = new MyNewPeerGroup()

    allProcessedMessages(pg).futureValue shouldBe List("a-1", "a-2", "a-3", "b-1", "b-2", "b-3")
  }

  it should "throw when multiple subscribers are added" in {
    val pg = new MyNewPeerGroup()

    pg.server.foreach(println)
    an[IllegalArgumentException] should be thrownBy pg.server.foreach(println)
  }

  private def allProcessedMessages(pg: MyNewPeerGroup): CancelableFuture[List[String]] = {
    val messagesObservable: Observable[String] = pg.server.mergeMap(_.in)
    messagesObservable.toListL.runToFuture
  }

  private def allProcessedMessages(
      pg: MyOldPeerGroup
  ): CancelableFuture[List[String]] = {
    val messagesObservable = pg.server.mergeMap(_.in)
    messagesObservable.toListL.runToFuture
  }

  private def allProcessedMessages(
      pg: MyConnectPeerGroup
  ): CancelableFuture[List[String]] = {
    val messagesObservable = pg.server.mergeMap(_.in)
    messagesObservable.toListL.runToFuture
  }

  // New peer group impl using
  // single subscriber only
  // cache until subscriber registered
  class MyNewPeerGroup() {

    val server = CacheUntilConnectStrictlyOneSubject[MyNewChannel]()

    server.onNext(new MyNewChannel("a"))
    server.onNext(new MyNewChannel("b"))
    server.onComplete()
  }

  class MyNewChannel(val id: String) {
    val in = CacheUntilConnectStrictlyOneSubject[String]()

    in.onNext(s"$id-1")
    in.onNext(s"$id-2")
    in.onNext(s"$id-3")
    in.onComplete()
  }

  // Connect peer group impl using
  // Allows many subscribers
  // Cache until connect
  class MyConnectPeerGroup() {

    val server = CacheUntilConnectSubject[MyConnectChannel]()

    server.onNext(new MyConnectChannel("a"))
    server.onNext(new MyConnectChannel("b"))
    server.onComplete()
  }

  class MyConnectChannel(val id: String) {
    val in = CacheUntilConnectSubject[String]()

    in.onNext(s"$id-1")
    in.onNext(s"$id-2")
    in.onNext(s"$id-3")
    in.onComplete()
  }

  // Original peer group using PublishSubject
  class MyOldPeerGroup() {

    val server = PublishSubject[MyOldChannel]()

    server.onNext(new MyOldChannel("a"))
    server.onNext(new MyOldChannel("b"))
    server.onComplete()
  }

  class MyOldChannel(val id: String) {
    val in = PublishSubject[String]()

    in.onNext(s"$id-1")
    in.onNext(s"$id-2")
    in.onNext(s"$id-3")
    in.onComplete()
  }

  // Observers in PeerGroup:
  // Use Stop to shutdown the peer group
  // Observers on message stream:
  // Need to find a way to register the message observer
  // Use Stop to terminate a channel

//  class ObserverChannel
//  class ObserverPeerGroup()
}
