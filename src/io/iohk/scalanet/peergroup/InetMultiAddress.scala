package io.iohk.scalanet.peergroup

import java.net.{InetAddress, InetSocketAddress}

case class InetMultiAddress(private[scalanet] val inetSocketAddress: InetSocketAddress) {

  val inetAddress: InetAddress = inetSocketAddress.getAddress

  def canEqual(other: Any): Boolean = other.isInstanceOf[InetMultiAddress]

  override def equals(other: Any): Boolean = other match {
    case that: InetMultiAddress =>
      (that canEqual this) &&
        inetAddress == that.inetAddress
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(inetAddress)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}