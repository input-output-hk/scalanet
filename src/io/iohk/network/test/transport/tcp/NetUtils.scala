package io.iohk.network.transport.tcp

import java.io.OutputStream
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.ByteBuffer

import io.iohk.network.{ NodeId, PeerConfig, TransportConfig}
import io.iohk.network.NodeId.nodeIdBytes
import io.iohk.network.discovery.NetworkDiscovery
import io.iohk.network.transport.{FrameHeader, Transports}
import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito.when
import org.scalacheck.Gen
import org.scalacheck.Arbitrary._
import org.scalatest.mockito.MockitoSugar.mock

import scala.collection.mutable
import scala.util.Random

object NetUtils {

  def writeTo(address: InetSocketAddress, bytes: Array[Byte]): Unit = {
    val socket = new Socket(address.getHostName, address.getPort)
    val out: OutputStream = socket.getOutputStream
    try {
      out.write(bytes)
    } finally {
      out.close()
    }
  }

  def aRandomNodeId(): NodeId =
    NodeId(randomBytes(nodeIdBytes))

  def aRandomAddress(): InetSocketAddress = {
    val s = new ServerSocket(0)
    try {
      new InetSocketAddress("localhost", s.getLocalPort)
    } finally {
      s.close()
    }
  }

  def discardMessages[Addr, T](remoteAddress: Addr, message: T): Unit = ()

  def logMessages[T](messages: mutable.ListBuffer[T])(remoteAddress: InetSocketAddress, message: T): Unit =
    messages += message

  def isListening(address: InetSocketAddress): Boolean = {
    try {
      new Socket(address.getHostName, address.getPort).close()
      true
    } catch {
      case e: Exception =>
        false
    }
  }

  def toArray(b: ByteBuffer): Array[Byte] = {
    val a = new Array[Byte](b.remaining())
    b.get(a)
    a
  }

  def randomBytes(n: Int): Array[Byte] = {
    val a = new Array[Byte](n)
    Random.nextBytes(a)
    a
  }

  def concatenate(buffs: Seq[ByteBuffer]): ByteBuffer = {
    val allocSize = buffs.foldLeft(0)((acc, nextBuff) => acc + nextBuff.capacity())

    val b0 = ByteBuffer.allocate(allocSize)

    (buffs.foldLeft(b0)((accBuff, nextBuff) => accBuff.put(nextBuff)): java.nio.Buffer).flip().asInstanceOf[ByteBuffer]
  }

  def forwardPort(srcPort: Int, dst: InetSocketAddress): PortForward =
    new PortForward(srcPort, dst)

  object NetUtilsGen {
    val genNodeId: Gen[NodeId] = Gen.listOfN(NodeId.nodeIdBytes, arbitrary[Byte]).map(NodeId(_))

    val genPeerInfo: Gen[PeerConfig] = for {
      nodeId <- genNodeId
    } yield {
      val address = aRandomAddress()
      val messageTtl = FrameHeader.defaultTtl
      PeerConfig(nodeId, TransportConfig(Some(TcpTransportConfig(address)), messageTtl))
    }
  }

  case class NetworkFixture(
                             nodeId: NodeId,
                             peerConfig: PeerConfig,
                             networkDiscovery: NetworkDiscovery,
                             transports: Transports
                           )

  def randomNetworkFixture(messageTtl: Int = FrameHeader.defaultTtl): NetworkFixture = {

    val tcpAddress: InetSocketAddress = aRandomAddress()
    val configuration = TransportConfig(Some(TcpTransportConfig(tcpAddress)), messageTtl)

    val nodeId = NodeId(randomBytes(NodeId.nodeIdBytes))

    val networkDiscovery: NetworkDiscovery = mock[NetworkDiscovery]

    val peerConfig = PeerConfig(nodeId, configuration)

    NetworkFixture(nodeId, peerConfig, networkDiscovery, new Transports(peerConfig))
  }

  def networkFixtures(fixtures: NetworkFixture*)(testCode: Seq[NetworkFixture] => Any): Unit = {
    try {
      testCode(fixtures)
    } finally {
      fixtures.foreach(fixture => fixture.transports.shutdown())
    }
  }

  def forTwoArbitraryNetworkPeers(testCode: (NetworkFixture, NetworkFixture) => Any): Unit = {
    val bootstrapNode = randomNetworkFixture()
    val otherNode = randomNetworkFixture()
    nodesArePeers(bootstrapNode, otherNode)
    networkFixtures(bootstrapNode, otherNode)(_ => testCode(bootstrapNode, otherNode))
  }

  def nodesArePeers(node1: NetworkFixture, node2: NetworkFixture): Unit = {
    when(node1.networkDiscovery.nearestPeerTo(node2.nodeId)).thenReturn(Some(node2.peerConfig))
    when(node2.networkDiscovery.nearestPeerTo(node1.nodeId)).thenReturn(Some(node1.peerConfig))
    when(node1.networkDiscovery.nearestNPeersTo(meq[NodeId](node1.nodeId), any[Int])).thenReturn(Seq(node2.peerConfig))
    when(node2.networkDiscovery.nearestNPeersTo(meq[NodeId](node2.nodeId), any[Int])).thenReturn(Seq(node1.peerConfig))
  }

  def nodesArePeers(nodes: List[NetworkFixture]): Unit = {
    nodes.foreach { node =>
      val neighbours = nodes.filterNot(n => n == node)
      when(node.networkDiscovery.nearestNPeersTo(meq[NodeId](node.nodeId), any[Int]))
        .thenReturn(neighbours.map(_.peerConfig))

      neighbours.foreach { neighbour =>
        when(neighbour.networkDiscovery.nearestPeerTo(node.nodeId)).thenReturn(Some(node.peerConfig))
      }
    }
  }

  def forNArbitraryNetworkPeers(n: Int)(testCode: Seq[NetworkFixture] => Any): Unit = {
    val nodes = Range.inclusive(1, n).map(_ => randomNetworkFixture()).toList
    nodesArePeers(nodes)
    networkFixtures(nodes: _*)(_ => testCode(nodes))
  }

}
