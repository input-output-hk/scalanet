package io.iohk.scalanet.peergroup

import java.net.{InetAddress, InetSocketAddress}

/**
  * TCP and UDP (and other socket-based protocols) have a problem where addressing and multiplexing are coupled.
  * This means that, in TCP world a single node can have multiple addresses. Even though port numbers are used
  * to support multiplexing, those port numbers leak into the address space.
  *
  * This leads to a tricky problem. On the one hand, a client cannot obtain a connection to another node without
  * specifying a port number. On the other, if a server receives two inbound connections from a client, it will
  * read two separate addresses as the remote address from the client (e.g. client:60441, client:60442), even
  * though both requests are from the same node.
  *
  * This class provides a solution to the problem. Firstly, for clients, it wraps an InetSocketAddress
  * (i.e. a host:port combo) so that clients can specify the port number this way. Secondly, for servers
  * it overrides equals/hashcode to ignore the port number. Therefore, if the server compares the addresses
  * of two connections from the same node (for example in using them as map keys), it will correctly determine that
  * they are from the same node.
  *
  * @param inetSocketAddress a host:port combo address.
  */
case class InetMultiAddress(inetSocketAddress: InetSocketAddress) {
  private val inetAddress: InetAddress = inetSocketAddress.getAddress

  def canEqual(other: Any): Boolean = other.isInstanceOf[InetMultiAddress]

  override def equals(other: Any): Boolean = other match {
    case that: InetMultiAddress =>
      (that canEqual this) &&
        inetAddress == that.inetAddress && this.inetSocketAddress.getPort == that.inetSocketAddress.getPort
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(inetAddress)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}
