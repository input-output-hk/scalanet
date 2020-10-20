package io.iohk.scalanet.discovery.ethereum.v4

import cats.implicits._
import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg, Signature}
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import io.iohk.scalanet.discovery.ethereum.codecs.DefaultCodecs._
import io.iohk.scalanet.discovery.ethereum.v4.DiscoveryNetwork.Peer
import io.iohk.scalanet.discovery.ethereum.v4.Payload.Ping
import io.iohk.scalanet.discovery.ethereum.v4.Payload._
import io.iohk.scalanet.discovery.ethereum.v4.mocks.{MockSigAlg, MockPeerGroup, MockChannel}
import io.iohk.scalanet.peergroup.Channel.ChannelEvent
import io.iohk.scalanet.peergroup.Channel.MessageReceived
import io.iohk.scalanet.NetUtils.aRandomAddress
import java.net.InetSocketAddress
import monix.execution.Scheduler
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NoStackTrace
import scala.collection.SortedMap
import scodec.bits.{BitVector, ByteVector}
import monix.tail.Iterant
import java.net.InetAddress

class DiscoveryNetworkSpec extends AsyncFlatSpec with Matchers {
  import DiscoveryNetworkSpec._

  def test(fixture: Fixture) = {
    fixture.test.runToFuture
  }

  behavior of "ping"

  it should "send an unexpired correctly versioned Ping Packet with the the local and remote addresses" in test {
    new Fixture {
      val remoteENRSeq = 123L

      override val test = for {
        _ <- network.ping(remotePeer)(None)
        _ <- network.ping(remotePeer)(Some(remoteENRSeq))

        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        msg1 <- channel.nextMessageFromSUT()
        msg2 <- channel.nextMessageFromSUT()
      } yield {
        channel.isClosed shouldBe true

        assertMessageFrom(publicKey, msg1) {
          case Ping(version, from, to, expiration, enrSeq) =>
            version shouldBe 4
            from shouldBe toNodeAddress(localAddress)
            to shouldBe toNodeAddress(remoteAddress)
            assertExpirationSet(expiration)
            enrSeq shouldBe empty
        }

        assertMessageFrom(publicKey, msg2) {
          case Ping(_, _, _, _, enrSeq) =>
            enrSeq shouldBe Some(remoteENRSeq)
        }
      }
    }
  }

  it should "return None if the peer times out" in test {
    new Fixture {
      override val test = for {
        result <- network.ping(remotePeer)(None)
      } yield {
        result shouldBe None
      }
    }
  }

  it should "return Some ENRSEQ if the peer responds" in test {
    new Fixture {
      val remoteENRSeq = 123L

      override val test = for {
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        pinging <- network.ping(remotePeer)(None).start

        msg <- channel.nextMessageFromSUT()
        packet = assertPacketReceived(msg)
        _ <- channel.sendPayloadToSUT(
          Pong(
            to = toNodeAddress(remoteAddress),
            pingHash = packet.hash,
            expiration = validExpiration,
            enrSeq = Some(remoteENRSeq)
          ),
          remotePrivateKey
        )

        maybeRemoteENRSeq <- pinging.join
      } yield {
        maybeRemoteENRSeq shouldBe Some(Some(remoteENRSeq))
      }
    }
  }

  it should "return None if the Pong hash doesn't match the Ping" in test {
    new Fixture {
      override val test = for {
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        pinging <- network.ping(remotePeer)(None).start

        msg <- channel.nextMessageFromSUT()
        packet = assertPacketReceived(msg)
        _ <- channel.sendPayloadToSUT(
          Pong(
            toNodeAddress(remoteAddress),
            pingHash = Hash(packet.hash.reverse),
            expiration = validExpiration,
            enrSeq = None
          ),
          remotePrivateKey
        )

        maybeRemoteENRSeq <- pinging.join
      } yield {
        maybeRemoteENRSeq shouldBe empty
      }
    }
  }

  it should "return None if the Pong is expired" in test {
    new Fixture {
      override val test = for {
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        pinging <- network.ping(remotePeer)(None).start

        msg <- channel.nextMessageFromSUT()
        packet = assertPacketReceived(msg)
        _ <- channel.sendPayloadToSUT(
          Pong(
            toNodeAddress(remoteAddress),
            pingHash = packet.hash,
            expiration = invalidExpiration,
            enrSeq = None
          ),
          remotePrivateKey
        )

        maybeRemoteENRSeq <- pinging.join
      } yield {
        maybeRemoteENRSeq shouldBe empty
      }
    }
  }

  it should "return None if the Pong is signed by an unexpected key" in test {
    new Fixture {
      val (_, unexpectedPrivateKey) = randomKeyPair

      override val test = for {
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        pinging <- network.ping(remotePeer)(None).start

        msg <- channel.nextMessageFromSUT()
        packet = assertPacketReceived(msg)
        _ <- channel.sendPayloadToSUT(
          Pong(
            to = toNodeAddress(remoteAddress),
            pingHash = packet.hash,
            expiration = validExpiration,
            enrSeq = None
          ),
          unexpectedPrivateKey
        )

        maybeRemoteENRSeq <- pinging.join
      } yield {
        maybeRemoteENRSeq shouldBe empty
      }
    }
  }

  behavior of "findNode"

  it should "send an unexpired FindNode Packet with the given target" in test {
    new Fixture {
      val (targetPublicKey, _) = randomKeyPair

      override val test = for {
        _ <- network.findNode(remotePeer)(targetPublicKey)

        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        msg <- channel.nextMessageFromSUT()
      } yield {
        channel.isClosed shouldBe true

        assertMessageFrom(publicKey, msg) {
          case FindNode(target, expiration) =>
            target shouldBe targetPublicKey
            assertExpirationSet(expiration)
        }
      }
    }
  }

  it should "return None if the peer times out" in test {
    new Fixture {
      override val test = for {
        result <- network.findNode(remotePeer)(remotePublicKey)
      } yield {
        result shouldBe None
      }
    }
  }

  it should "return Some Nodes if the peer responds" in test {
    new Fixture {
      override lazy val config = defaultConfig.copy(
        kademliaTimeout = 250.millis
      )

      override val test = for {
        finding <- network.findNode(remotePeer)(remotePublicKey).start
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        _ <- channel.nextMessageFromSUT()
        response = Neighbors(
          nodes = List(Node(remotePublicKey, toNodeAddress(remoteAddress))),
          expiration = validExpiration
        )
        _ <- channel.sendPayloadToSUT(response, remotePrivateKey)
        nodes <- finding.join
      } yield {
        nodes shouldBe Some(response.nodes)
      }
    }
  }

  it should "collect responses up to the timeout" in test {
    new Fixture {
      override lazy val config = defaultConfig.copy(
        kademliaTimeout = 500.millis,
        kademliaBucketSize = 16
      )

      val randomNodes = List.fill(config.kademliaBucketSize)(randomNode)

      override val test = for {
        finding <- network.findNode(remotePeer)(remotePublicKey).start
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        _ <- channel.nextMessageFromSUT()

        send = (nodes: List[Node]) => {
          val neighbors = Neighbors(nodes, validExpiration)
          channel.sendPayloadToSUT(neighbors, remotePrivateKey)
        }

        _ <- send(randomNodes.take(3))
        _ <- send(randomNodes.drop(3).take(7))
        _ <- send(randomNodes.drop(10)).delayExecution(config.kademliaTimeout + 50.millis)

        nodes <- finding.join
      } yield {
        nodes shouldBe Some(randomNodes.take(10))
      }
    }
  }

  it should "collect responses up to the bucket size" in test {
    new Fixture {
      override lazy val config = defaultConfig.copy(
        kademliaTimeout = 7.seconds,
        kademliaBucketSize = 16
      )

      val randomGroups = List.fill(config.kademliaBucketSize + 6)(randomNode).grouped(6).toList

      override val test = for {
        finding <- network.findNode(remotePeer)(remotePublicKey).start
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        _ <- channel.nextMessageFromSUT()

        send = (nodes: List[Node]) => {
          val neighbors = Neighbors(nodes, validExpiration)
          channel.sendPayloadToSUT(neighbors, remotePrivateKey)
        }

        _ <- randomGroups.traverse(send)

        nodes <- finding.join
      } yield {
        nodes should not be empty
        nodes.get should have size config.kademliaBucketSize
        nodes.get shouldBe randomGroups.flatten.take(config.kademliaBucketSize)
      }
    }
  }

  it should "ignore expired neighbors" in test {
    new Fixture {
      override lazy val config = defaultConfig.copy(
        kademliaTimeout = 7.seconds,
        kademliaBucketSize = 16
      )

      override val test = for {
        finding <- network.findNode(remotePeer)(remotePublicKey).start
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        _ <- channel.nextMessageFromSUT()

        neighbors = Neighbors(
          nodes = List(Node(remotePublicKey, toNodeAddress(remoteAddress))),
          expiration = invalidExpiration
        )
        _ <- channel.sendPayloadToSUT(neighbors, remotePrivateKey)

        nodes <- finding.join
      } yield {
        nodes shouldBe empty
      }
    }
  }

  behavior of "enrRequest"

  it should "send an unexpired ENRRequest Packet" in test {
    new Fixture {

      override val test = for {
        _ <- network.enrRequest(remotePeer)(())

        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        msg <- channel.nextMessageFromSUT()
      } yield {
        channel.isClosed shouldBe true

        assertMessageFrom(publicKey, msg) {
          case ENRRequest(expiration) =>
            assertExpirationSet(expiration)
        }
      }
    }
  }

  it should "return None if the peer times out" in test {
    new Fixture {
      override val test = for {
        result <- network.enrRequest(remotePeer)(())
      } yield {
        result shouldBe None
      }
    }
  }

  it should "return Some ENR if the peer responds" in test {
    new Fixture {
      override val test = for {
        requesting <- network.enrRequest(remotePeer)(()).start
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        msg <- channel.nextMessageFromSUT()
        packet = assertPacketReceived(msg)
        _ <- channel.sendPayloadToSUT(
          ENRResponse(
            requestHash = packet.hash,
            enr = remoteENR
          ),
          remotePrivateKey
        )

        maybeENR <- requesting.join
      } yield {
        maybeENR shouldBe Some(remoteENR)
      }
    }
  }

  it should "ignore ENRResponse if the request hash doesn't match" in test {
    new Fixture {
      override val test = for {
        requesting <- network.enrRequest(remotePeer)(()).start
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        msg <- channel.nextMessageFromSUT()
        packet = assertPacketReceived(msg)
        _ <- channel.sendPayloadToSUT(
          ENRResponse(
            requestHash = Hash(packet.hash.reverse),
            enr = remoteENR
          ),
          remotePrivateKey
        )

        maybeENR <- requesting.join
      } yield {
        maybeENR shouldBe None
      }
    }
  }

  behavior of "startHandling"

  it should "start handling requests in the background" in test {
    new Fixture {
      override val test = for {
        token <- network.startHandling {
          StubDiscoveryRPC(
            ping = _ => _ => Task.pure(Some(None))
          )
        }
        // The fact that we moved on from `startHandling` shows that it's not
        // running in the foreground.
        channel <- peerGroup.createServerChannel(from = remoteAddress)
        ping = Ping(4, toNodeAddress(remoteAddress), toNodeAddress(localAddress), validExpiration, None)
        _ <- channel.sendPayloadToSUT(ping, remotePrivateKey)
        msg <- channel.nextMessageFromSUT()
      } yield {
        msg should not be empty
      }
    }
  }

  // This is testing that we didn't do something silly in the handler such as
  // for example use flatMap with Iterants that could wait until the messages
  // from earlier channels are exhausted before it would handle later ones.
  it should "handle multiple channels in concurrently" in test {
    new Fixture {
      val remotes = List.fill(5)(aRandomAddress -> randomKeyPair)

      override val test = for {
        _ <- network.startHandling {
          StubDiscoveryRPC(
            ping = _ => _ => Task.pure(Some(None))
          )
        }
        channels <- remotes.traverse {
          case (from, _) => peerGroup.createServerChannel(from)
        }
        ping = Ping(4, toNodeAddress(remoteAddress), toNodeAddress(localAddress), validExpiration, None)
        _ <- (channels zip remotes).traverse {
          case (channel, (remoteAddress, (_, remotePrivateKey))) =>
            channel.sendPayloadToSUT(ping, remotePrivateKey)
        }
        messages <- channels.traverse(_.nextMessageFromSUT())
      } yield {
        Inspectors.forAll(messages)(_ should not be empty)
      }
    }
  }

  it should "stop handling when canceled" in test {
    new Fixture {
      override val test = for {
        token <- network.startHandling {
          StubDiscoveryRPC(
            ping = _ => _ => Task.pure(Some(None))
          )
        }
        channel <- peerGroup.createServerChannel(from = remoteAddress)
        ping = Ping(4, toNodeAddress(remoteAddress), toNodeAddress(localAddress), validExpiration, None)

        _ <- channel.sendPayloadToSUT(ping, remotePrivateKey)
        msg1 <- channel.nextMessageFromSUT()

        _ <- token.cancel

        _ <- channel.sendPayloadToSUT(ping, remotePrivateKey)
        msg2 <- channel.nextMessageFromSUT()
      } yield {
        msg1 should not be empty
        msg2 shouldBe empty
      }
    }
  }

  it should "close idle channels" in test {
    new Fixture {
      override lazy val config = defaultConfig.copy(
        messageExpiration = 500.millis
      )

      override val test = for {
        _ <- network.startHandling(StubDiscoveryRPC())
        channel <- peerGroup.createServerChannel(from = remoteAddress)
        _ <- Task.sleep(config.messageExpiration + 100.millis)
      } yield {
        channel.isClosed shouldBe true
      }
    }
  }

  it should "ignore incoming response messages" in test {
    new Fixture {
      override val test = for {
        _ <- network.startHandling(
          StubDiscoveryRPC(
            findNode = _ => _ => Task.pure(Some(List(randomNode)))
          )
        )
        channel <- peerGroup.createServerChannel(from = remoteAddress)
        _ <- channel.sendPayloadToSUT(Neighbors(Nil, validExpiration), remotePrivateKey)
        msg1 <- channel.nextMessageFromSUT()
        _ <- channel.sendPayloadToSUT(FindNode(remotePublicKey, validExpiration), remotePrivateKey)
        msg2 <- channel.nextMessageFromSUT()
      } yield {
        msg1 shouldBe empty
        msg2 should not be empty
      }
    }
  }

  it should "respond with an unexpired Pong with the correct hash if the handler returns Some ENRSEQ" in test {
    new Fixture {
      val localENRSeq = 123L

      override val test = for {
        _ <- network.startHandling {
          StubDiscoveryRPC(
            ping = _ => _ => Task(Some(Some(localENRSeq)))
          )
        }
        channel <- peerGroup.createServerChannel(from = remoteAddress)
        ping = Ping(4, toNodeAddress(remoteAddress), toNodeAddress(localAddress), validExpiration, None)
        packet <- channel.sendPayloadToSUT(ping, remotePrivateKey)
        msg <- channel.nextMessageFromSUT()
      } yield {
        assertMessageFrom(publicKey, msg) {
          case Pong(to, pingHash, expiration, enrSeq) =>
            to shouldBe toNodeAddress(localAddress)
            pingHash shouldBe packet.hash
            assertExpirationSet(expiration)
            enrSeq shouldBe Some(localENRSeq)
        }
      }
    }
  }

  it should "respond with multiple unexpired Neighbors each within the packet size limit, in total no more than the bucket size, if the handler returns Some Nodes" in test {
    new Fixture {
      val randomNodes = List.fill(config.kademliaBucketSize * 2)(randomNode)

      override val test = for {
        _ <- network.startHandling {
          StubDiscoveryRPC(
            findNode = _ => _ => Task(Some(randomNodes))
          )
        }
        channel <- peerGroup.createServerChannel(from = remoteAddress)
        findNode = FindNode(remotePublicKey, validExpiration)
        packet <- channel.sendPayloadToSUT(findNode, remotePrivateKey)
        msgs <- Iterant
          .repeatEvalF(channel.nextMessageFromSUT())
          .takeWhile(_.isDefined)
          .toListL
      } yield {
        // We should receive at least 2 messages because of the packet size limit.
        msgs(0) should not be empty
        msgs(1) should not be empty

        Inspectors.forAll(msgs) {
          case Some(MessageReceived(packet)) =>
            val packetSize = packet.hash.size + packet.signature.size + packet.data.size
            assert(packetSize <= Packet.MaxPacketBitsSize)
          case _ =>
        }

        val nodes = msgs.map { msg =>
          assertMessageFrom(publicKey, msg) {
            case Neighbors(nodes, expiration) =>
              assertExpirationSet(expiration)
              nodes
          }
        }
        nodes.flatten shouldBe randomNodes.take(config.kademliaBucketSize)
      }
    }
  }

  it should "respond with an ENRResponse with the correct hash if the handler returns Some ENR" in test {
    new Fixture {
      override val test = for {
        _ <- network.startHandling {
          StubDiscoveryRPC(
            enrRequest = _ => _ => Task(Some(localENR))
          )
        }
        channel <- peerGroup.createServerChannel(from = remoteAddress)
        enrRequest = ENRRequest(validExpiration)
        packet <- channel.sendPayloadToSUT(enrRequest, remotePrivateKey)
        msg <- channel.nextMessageFromSUT()
      } yield {
        msg should not be empty
        assertMessageFrom(publicKey, msg) {
          case ENRResponse(requestHash, enr) =>
            requestHash shouldBe packet.hash
            enr shouldBe localENR
        }
      }
    }
  }

  GenericRPCFixture.rpcs.foreach { rpc =>
    it should s"not respond to $rpc if the handler returns None" in test {
      new GenericRPCFixture {
        override val test = for {
          _ <- network.startHandling(handleWithNone)
          channel <- peerGroup.createServerChannel(from = remoteAddress)
          request = requestMap(rpc)
          _ <- channel.sendPayloadToSUT(request, remotePrivateKey)
          msg <- channel.nextMessageFromSUT()
        } yield {
          msg shouldBe empty
        }
      }
    }

    it should s"not respond to $rpc if the request is expired" in test {
      new GenericRPCFixture {
        @volatile var called = false

        override val test = for {
          _ <- network.startHandling(
            handleWithSome.withEffect {
              Task { called = true }
            }
          )
          channel <- peerGroup.createServerChannel(from = remoteAddress)
          (request: Payload) = requestMap(rpc) match {
            case p: Payload.HasExpiration[_] => p.withExpiration(invalidExpiration)
            case p => p
          }
          _ <- channel.sendPayloadToSUT(request, remotePrivateKey)
          msg <- channel.nextMessageFromSUT()
        } yield {
          msg shouldBe empty
          called shouldBe false
        }
      }
    }

    it should s"respond to $rpc if the request is expired but within the clock drift" in test {
      new GenericRPCFixture {

        override lazy val config = defaultConfig.copy(
          maxClockDrift = 15.seconds
        )

        override val test = for {
          _ <- network.startHandling(handleWithSome)
          channel <- peerGroup.createServerChannel(from = remoteAddress)
          (request: Payload) = requestMap(rpc) match {
            case p: Payload.HasExpiration[_] => p.withExpiration(System.currentTimeMillis - 5000)
            case p => p
          }
          _ <- channel.sendPayloadToSUT(request, remotePrivateKey)
          msg <- channel.nextMessageFromSUT()
        } yield {
          msg should not be empty
        }
      }
    }

    it should s"forward the caller to the $rpc handler" in test {
      new GenericRPCFixture {
        def assertCaller(caller: Caller) = Task {
          caller shouldBe Peer(remotePublicKey, remoteAddress)
        }

        override val test = for {
          _ <- network.startHandling {
            handleWithSome.withCallerEffect(assertCaller(_).void)
          }
          channel <- peerGroup.createServerChannel(from = remoteAddress)
          request = requestMap(rpc)
          _ <- channel.sendPayloadToSUT(request, remotePrivateKey)
          msg <- channel.nextMessageFromSUT()
        } yield {
          msg should not be empty
        }
      }
    }

    it should s"not stop processing $rpc requests if the handler throws" in test {
      new GenericRPCFixture {
        object TestException extends NoStackTrace

        // Only raising on the 1st call, to check that the 2nd succeeds.
        @volatile var raised = false

        def raiseOnFirst() = Task {
          if (!raised) {
            raised = true
            throw TestException
          }
        }

        override val test = for {
          _ <- network.startHandling {
            handleWithSome.withEffect(raiseOnFirst())
          }
          channel <- peerGroup.createServerChannel(from = remoteAddress)
          request = requestMap(rpc)
          _ <- channel.sendPayloadToSUT(request, remotePrivateKey)
          msg1 <- channel.nextMessageFromSUT()
          _ <- channel.sendPayloadToSUT(request, remotePrivateKey)
          msg2 <- channel.nextMessageFromSUT()
        } yield {
          msg1 shouldBe empty
          msg2 should not be empty
        }
      }
    }

    it should s"stop processing $rpc requests after an invalid packet" in test {
      new GenericRPCFixture {
        override val test = for {
          _ <- network.startHandling(handleWithSome)
          channel <- peerGroup.createServerChannel(from = remoteAddress)
          garbage = Packet(
            Hash(BitVector(randomBytes(1))),
            Signature(BitVector(randomBytes(2))),
            BitVector(randomBytes(3))
          )
          _ <- channel.sendMessageToSUT(garbage)
          msg1 <- channel.nextMessageFromSUT()
          request = requestMap(rpc)
          _ <- channel.sendPayloadToSUT(request, remotePrivateKey)
          msg2 <- channel.nextMessageFromSUT()
        } yield {
          msg1 shouldBe empty
          msg2 shouldBe empty
          channel.isClosed shouldBe true
        }
      }
    }
  }

  behavior of "getMaxNeighborsPerPacket"

  it should "correctly estimate the maximum number" in {
    val maxNeighborsPerPacket = DiscoveryNetwork.getMaxNeighborsPerPacket

    // We're using scodec encoding here, so it's not exactly the same as RLP,
    // but it should be less than the default Kademlia bucket size of 16.
    maxNeighborsPerPacket should be > 1
    maxNeighborsPerPacket should be < 16

    val randomIPv6Node = {
      val node = randomNode
      node.copy(address = node.address.copy(ip = InetAddress.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334")))
    }

    def packetSizeOfNNeighbors(n: Int) = {
      val neighbours = Neighbors(List.fill(n)(randomIPv6Node), System.currentTimeMillis)
      val (_, privateKey) = randomKeyPair
      val packet = Packet.pack(neighbours, privateKey).require
      val packetSize = packet.hash.size + packet.signature.size + packet.data.size
      packetSize
    }

    assert(packetSizeOfNNeighbors(maxNeighborsPerPacket) <= Packet.MaxPacketBitsSize)
    assert(packetSizeOfNNeighbors(maxNeighborsPerPacket + 1) > Packet.MaxPacketBitsSize)
  }
}

object DiscoveryNetworkSpec extends Matchers {
  implicit val scheduler: Scheduler = Scheduler.Implicits.global
  implicit val sigalg: SigAlg = new MockSigAlg

  def randomBytes(n: Int) = {
    val bytes = Array.ofDim[Byte](n)
    Random.nextBytes(bytes)
    bytes
  }

  def randomKeyPair: (PublicKey, PrivateKey) = {
    // Using mock keys with the MockSigAlg, it returns the private as public.
    assert(sigalg.PrivateKeyBytesSize == sigalg.PublicKeyBytesSize)
    val privateKey = PrivateKey(BitVector(randomBytes(sigalg.PrivateKeyBytesSize)))
    val publicKey = PublicKey(privateKey)
    publicKey -> privateKey
  }

  def randomNode: Node = {
    val (publicKey, _) = randomKeyPair
    val address = aRandomAddress()
    Node(publicKey, toNodeAddress(address))
  }

  def toNodeAddress(address: InetSocketAddress): Node.Address =
    Node.Address(
      ip = address.getAddress,
      udpPort = address.getPort,
      tcpPort = address.getPort
    )

  val defaultConfig = DiscoveryConfig(
    requestTimeout = 100.millis,
    messageExpiration = 60.seconds,
    kademliaTimeout = 250.millis,
    kademliaBucketSize = 16,
    maxClockDrift = Duration.Zero
  )

  trait Fixture {
    // Implement `test` to assert something.
    def test: Task[Assertion]

    lazy val config = defaultConfig

    lazy val localAddress = aRandomAddress
    // Keys for the System Under Test.
    lazy val (publicKey, privateKey) = randomKeyPair

    lazy val localENR = EthereumNodeRecord(
      seq = 456L,
      signature = Signature(BitVector(randomBytes(65))),
      attrs = SortedMap(
        EthereumNodeRecord.Keys.id -> ByteVector("v4".getBytes),
        EthereumNodeRecord.Keys.ip -> ByteVector(localAddress.getAddress.getAddress),
        EthereumNodeRecord.Keys.udp -> ByteVector(localAddress.getPort)
      )
    )

    // A random peer to talk to.
    lazy val remoteAddress = aRandomAddress
    lazy val (remotePublicKey, remotePrivateKey) = randomKeyPair
    lazy val remotePeer = Peer(remotePublicKey, remoteAddress)

    lazy val remoteENR = EthereumNodeRecord(
      seq = 123L,
      signature = Signature(BitVector(randomBytes(65))),
      attrs = SortedMap(
        EthereumNodeRecord.Keys.id -> ByteVector("v4".getBytes),
        EthereumNodeRecord.Keys.ip -> ByteVector(remoteAddress.getAddress.getAddress),
        EthereumNodeRecord.Keys.udp -> ByteVector(remoteAddress.getPort)
      )
    )

    lazy val peerGroup: MockPeerGroup[InetSocketAddress, Packet] =
      new MockPeerGroup(
        processAddress = localAddress
      )

    lazy val network: DiscoveryNetwork[InetSocketAddress] =
      DiscoveryNetwork[InetSocketAddress](
        peerGroup = peerGroup,
        privateKey = privateKey,
        toNodeAddress = toNodeAddress,
        config = config
      ).runSyncUnsafe()

    def assertExpirationSet(expiration: Long) =
      expiration shouldBe (System.currentTimeMillis + config.messageExpiration.toMillis) +- 3000

    def validExpiration =
      System.currentTimeMillis + config.messageExpiration.toMillis

    // Anything in the past is invalid.
    def invalidExpiration =
      System.currentTimeMillis - 1

    implicit class ChannelOps(channel: MockChannel[InetSocketAddress, Packet]) {
      def sendPayloadToSUT(
          payload: Payload,
          privateKey: PrivateKey
      ): Task[Packet] = {
        for {
          packet <- Task(Packet.pack(payload, privateKey).require)
          _ <- channel.sendMessageToSUT(packet)
        } yield packet
      }
    }

    type Caller = Peer[InetSocketAddress]

    case class StubDiscoveryRPC(
        ping: DiscoveryRPC.Call[Caller, DiscoveryRPC.Proc.Ping] = _ => ???,
        findNode: DiscoveryRPC.Call[Caller, DiscoveryRPC.Proc.FindNode] = _ => ???,
        enrRequest: DiscoveryRPC.Call[Caller, DiscoveryRPC.Proc.ENRRequest] = _ => ???
    ) extends DiscoveryRPC[Caller]
  }

  // Facilitate tests that are common among all RPC calls.
  trait GenericRPCFixture extends Fixture {
    val requestMap: Map[String, Payload] = Map(
      "ping" -> Ping(4, toNodeAddress(remoteAddress), toNodeAddress(localAddress), validExpiration, None),
      "findNode" -> FindNode(remotePublicKey, validExpiration),
      "enrRequest" -> ENRRequest(validExpiration)
    )

    val handleWithNone = StubDiscoveryRPC(
      ping = _ => _ => Task.pure(None),
      findNode = _ => _ => Task.pure(None),
      enrRequest = _ => _ => Task.pure(None)
    )

    val handleWithSome = StubDiscoveryRPC(
      ping = _ => _ => Task.pure(Some(None)),
      findNode = _ => _ => Task.pure(Some(List(randomNode))),
      enrRequest = _ => _ => Task.pure(Some(localENR))
    )

    implicit class StubDiscoveryRPCOps(stub: StubDiscoveryRPC) {
      def withEffect(task: Task[Unit]): StubDiscoveryRPC = {
        stub.copy(
          ping = caller => req => task >> stub.ping(caller)(req),
          findNode = caller => req => task >> stub.findNode(caller)(req),
          enrRequest = caller => req => task >> stub.enrRequest(caller)(req)
        )
      }

      def withCallerEffect(f: Caller => Task[Unit]): StubDiscoveryRPC = {
        stub.copy(
          ping = caller => req => f(caller) >> stub.ping(caller)(req),
          findNode = caller => req => f(caller) >> stub.findNode(caller)(req),
          enrRequest = caller => req => f(caller) >> stub.enrRequest(caller)(req)
        )
      }
    }

  }
  object GenericRPCFixture {
    val rpcs = List("ping", "findNode", "enrRequest")
  }

  def assertPacketReceived(maybeEvent: Option[ChannelEvent[Packet]]): Packet = {
    maybeEvent match {
      case Some(event) =>
        event match {
          case MessageReceived(packet) =>
            packet
          case other =>
            fail(s"Expected MessageReceived; got $other")
        }

      case None =>
        fail("Channel event was empty.")
    }
  }

  def assertMessageFrom[T](publicKey: PublicKey, maybeEvent: Option[ChannelEvent[Packet]])(
      pf: PartialFunction[Payload, T]
  ): T = {
    val packet = assertPacketReceived(maybeEvent)
    val (payload, remotePublicKey) =
      Packet.unpack(packet).require

    remotePublicKey shouldBe publicKey

    if (pf.isDefinedAt(payload))
      pf(payload)
    else
      fail(s"Unexpected payload: $payload")
  }
}
