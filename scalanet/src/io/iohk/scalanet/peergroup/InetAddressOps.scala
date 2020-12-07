package io.iohk.scalanet.peergroup

import com.github.jgonian.ipmath.{Ipv6Range, Ipv4Range, Ipv4, Ipv6}
import java.net.{InetAddress, Inet4Address, Inet6Address}
import scala.language.implicitConversions
import com.github.jgonian.ipmath.AbstractIp

class InetAddressOps(val address: InetAddress) extends AnyVal {
  import InetAddressOps._

  def isIPv4: Boolean =
    address.isInstanceOf[Inet4Address]

  def isIPv6: Boolean =
    address.isInstanceOf[Inet6Address]

  def isSpecial: Boolean =
    address.isMulticastAddress || isIPv4 && isInRange4(special4) || isIPv6 && isInRange6(special6)

  def isLAN: Boolean =
    address.isLoopbackAddress || isIPv4 && isInRange4(lan4) || isIPv6 && isInRange6(lan6)

  def isUnspecified: Boolean =
    address == unspecified4 || address == unspecified6

  private def isInRange4(infos: List[Ipv4Range]): Boolean = {
    val ip = toIpv4
    infos.exists(_.contains(ip))
  }

  private def isInRange6(infos: List[Ipv6Range]): Boolean = {
    val ip = toIpv6
    infos.exists(_.contains(ip))
  }

  private def toIpv4 =
    Ipv4.of(address.getHostAddress)

  private def toIpv6 =
    Ipv6.of(address.getHostAddress)

  private def toAbstractIp: AbstractIp[_, _] =
    if (isIPv4) toIpv4 else toIpv6

  private def toInetAddress(ip: AbstractIp[_, _]) =
    InetAddress.getByName(ip.toString)

  /** Truncate the IP address to the first `prefixLength` bits. */
  def truncate(prefixLength: Int): InetAddress =
    toInetAddress(toAbstractIp.lowerBoundForPrefix(prefixLength))
}

object InetAddressOps {
  implicit def toInetAddressOps(address: InetAddress): InetAddressOps =
    new InetAddressOps(address)

  // https://tools.ietf.org/html/rfc5735.html
  private val unspecified4 = InetAddress.getByName("0.0.0.0")

  // https://tools.ietf.org/html/rfc2373.html
  private val unspecified6 = InetAddress.getByName("0:0:0:0:0:0:0:0")

  // https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry.xhtml
  private val special4 =
    List(
      "100.64.0.0/10", //	Shared Address Space
      "169.254.0.0/16", //	Link Local
      "192.0.0.0/24", // [2]	IETF Protocol Assignments
      "192.0.0.0/29", //	IPv4 Service Continuity Prefix
      "192.0.0.8/32", //	IPv4 dummy address
      "192.0.0.9/32", //	Port Control Protocol Anycast
      "192.0.0.10/32", //	Traversal Using Relays around NAT Anycast
      "192.0.0.170/32", //  NAT64/DNS64 Discovery
      "192.0.0.171/32", //	NAT64/DNS64 Discovery
      "192.0.2.0/24", //	Documentation (TEST-NET-1)
      "192.31.196.0/24", //	AS112-v4
      "192.52.193.0/24", //	AMT
      "192.88.99.0/24", //	Deprecated (6to4 Relay Anycast)
      "192.175.48.0/24", //	Direct Delegation AS112 Service
      "198.18.0.0/15", //	Benchmarking
      "198.51.100.0/24", //	Documentation (TEST-NET-2)
      "203.0.113.0/24", //	Documentation (TEST-NET-3)
      "240.0.0.0/4", //	Reserved
      "255.255.255.255/32" //	Limited Broadcast
    ).map(Ipv4Range.parse(_))

  // https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry.xhtml
  private val special6 =
    List(
      "100::/64",
      "2001::/32",
      "2001:1::1/128",
      "2001:2::/48",
      "2001:3::/32",
      "2001:4:112::/48",
      "2001:5::/32",
      "2001:10::/28",
      "2001:20::/28",
      "2001:db8::/32",
      "2002::/16"
    ).map(Ipv6Range.parse(_))

  private val lan4 =
    List(
      "0.0.0.0/8", //	"This host on this network"
      "10.0.0.0/8", // Private-Use
      "172.16.0.0/12", // Private-Use
      "192.168.0.0/16" // Private-Use
    ).map(Ipv4Range.parse(_))

  private val lan6 =
    List(
      "fe80::/10", // Link-Local
      "fc00::/7" // Unique-Local
    ).map(Ipv6Range.parse(_))
}
