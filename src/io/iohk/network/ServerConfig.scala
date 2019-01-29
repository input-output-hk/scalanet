package io.iohk.network
import java.net.{InetAddress, InetSocketAddress}

import com.typesafe.config.Config

class ServerConfig(val address: InetSocketAddress)

object ServerConfig {
  def apply(config: Config): ServerConfig = {
    val interface = config.getString("interface")
    val port = config.getInt("port")
    new ServerConfig(new InetSocketAddress(InetAddress.getByName(interface), port))
  }
}
