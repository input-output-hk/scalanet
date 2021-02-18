package io.iohk.scalanet.peergroup

import cats.effect.Resource
import cats.implicits._
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.peergroup.ReqResponseProtocol._
import io.iohk.scalanet.peergroup.TransportPeerGroupAsyncSpec.{DynamicTLS, DynamicUDP}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.{FramingConfig, PeerInfo}

import java.util.concurrent.{Executors, TimeUnit}
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{Assertion, AsyncFlatSpec, BeforeAndAfterAll}
import org.scalatest.Matchers._
import org.scalatest.prop.TableDrivenPropertyChecks._
import scodec.Codec

import scala.concurrent.{ExecutionContext, Future}
import scodec.codecs.implicits._

import java.net.InetSocketAddress

/**
  *
  * Spec to test different concurrency scenario on top of our low level peer groups.
  * FIXME Add more scenarios.
  *
  */
class TransportPeerGroupAsyncSpec extends AsyncFlatSpec with BeforeAndAfterAll {
  val threadPool = Executors.newFixedThreadPool(16)
  val testContext = ExecutionContext.fromExecutor(threadPool)
  implicit val scheduler = Scheduler(testContext)

  override def afterAll(): Unit = {
    threadPool.shutdown()
    threadPool.awaitTermination(60, TimeUnit.SECONDS)
    ()
  }

  private val rpcs = Table(
    ("Label", "Transport type"),
    ("UDP", DynamicUDP),
    ("DTLS", DynamicTLS)
  )
  forAll(rpcs) { (label, transportType) =>
    import TransportPeerGroupAsyncSpec._

    s"Request response on top of ${label}" should "exchange messages between clients sequentially" in taskTestCase {
      List.fill(2)(transportType.getProtocol[String](aRandomAddress())).sequence.use {
        case List(client1, client2) =>
          for {
            _ <- Task.parZip2(
              client1.startHandling(echoDoubleHandler).startAndForget,
              client2.startHandling(echoDoubleHandler).startAndForget
            )
            resp <- client1.send(msg1, client2.processAddress)
            resp2 <- client2.send(msg3, client1.processAddress)
            resp1 <- client1.send(msg2, client2.processAddress)
            resp3 <- client2.send(msg3 ++ msg1, client1.processAddress)
          } yield {
            resp shouldEqual msg1 ++ msg1
            resp1 shouldEqual msg2 ++ msg2
            resp2 shouldEqual msg3 ++ msg3
            resp3 shouldEqual (msg3 ++ msg1) ++ (msg3 ++ msg1)
          }
        case _ => fail()
      }
    }

    s"Request response on top of ${label}" should "exchange messages between clients concurrently" in taskTestCase {
      List.fill(3)(transportType.getProtocol[Int](aRandomAddress())).sequence.use {
        case List(client1, client2, client3) =>
          for {
            _ <- Task.parZip3(
              client1.startHandling(doublingHandler).startAndForget,
              client2.startHandling(doublingHandler).startAndForget,
              client3.startHandling(doublingHandler).startAndForget
            )
            responses <- Task.parZip3(
              client1.send(i, client2.processAddress),
              client2.send(j, client3.processAddress),
              client3.send(k, client1.processAddress)
            )
            (r1, r2, r3) = responses
            responses <- Task.parSequence((1 to 4).map { req =>
              if (req % 2 == 0) {
                client1.send(req, client3.processAddress)
              } else {
                client2.send(req, client3.processAddress)
              }
            })
          } yield {
            r1 shouldEqual 2 * i
            r2 shouldEqual 2 * j
            r3 shouldEqual 2 * k
            responses shouldEqual (1 to 4).map(2 * _)
          }
        case _ => fail()
      }
    }

    s"Request response on top of ${label}" should "exchange messages between clients concurrently for multiple messages" in taskTestCase {
      val client1Numbers = (1 to 20).toList
      val client2Numbers = (10 to 30).toList
      val client3Numbers = (20 to 40).toList
      List.fill(3)(transportType.getProtocol[Int](aRandomAddress())).sequence.use {
        case List(client1, client2, client3) =>
          for {
            - <- Task.parZip3(
              client1.startHandling(doublingHandler).startAndForget,
              client2.startHandling(doublingHandler).startAndForget,
              client3.startHandling(doublingHandler).startAndForget
            )
            responses <- Task.parZip3(
              Task.sequence(client1Numbers.map(num => client1.send(num, client3.processAddress))),
              Task.sequence(client2Numbers.map(num => client2.send(num, client3.processAddress))),
              Task.sequence(client3Numbers.map(num => client3.send(num, client1.processAddress)))
            )
            (resp1, resp2, resp3) = responses

          } yield {
            resp1 shouldEqual client1Numbers.map(2 * _)
            resp2 shouldEqual client2Numbers.map(2 * _)
            resp3 shouldEqual client3Numbers.map(2 * _)
          }
        case _ => fail()
      }
    }
  }
}

object TransportPeerGroupAsyncSpec {
  def taskTestCase(t: => Task[Assertion])(implicit s: Scheduler): Future[Assertion] =
    t.runToFuture

  val echoDoubleHandler: String => String = s => s ++ s
  val msg1 = "Hello"
  val msg2 = "Server"
  val msg3 = "This is the way"

  val doublingHandler: Int => Int = i => i * 2
  val i = 1
  val j = 2
  val k = 3

  val testFramingConfig = FramingConfig.buildStandardFrameConfig(192000, 4).getOrElse(fail())

  sealed abstract class TransportProtocol extends Product with Serializable {
    type AddressingType
    def getProtocol[M](
        address: InetSocketAddress
    )(implicit s: Scheduler, c: Codec[M]): Resource[Task, ReqResponseProtocol[AddressingType, M]]
  }
  case object DynamicUDP extends TransportProtocol {
    override type AddressingType = InetMultiAddress

    override def getProtocol[M](
        address: InetSocketAddress
    )(implicit s: Scheduler, c: Codec[M]): Resource[Task, ReqResponseProtocol[InetMultiAddress, M]] = {
      getDynamicUdpReqResponseProtocolClient(address)
    }
  }

  case object DynamicTLS extends TransportProtocol {
    override type AddressingType = PeerInfo

    override def getProtocol[M](
        address: InetSocketAddress
    )(implicit s: Scheduler, c: Codec[M]): Resource[Task, ReqResponseProtocol[PeerInfo, M]] = {
      getTlsReqResponseProtocolClient(testFramingConfig)(address)
    }
  }

}
