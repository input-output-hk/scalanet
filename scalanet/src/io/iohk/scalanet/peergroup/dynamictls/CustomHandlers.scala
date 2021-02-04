package io.iohk.scalanet.peergroup.dynamictls

import com.github.benmanes.caffeine.cache.Caffeine
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.ipfilter.AbstractRemoteAddressFilter

import java.net.{InetAddress, InetSocketAddress}

private[scalanet] object CustomHandlers {
  // to share handlers between pipelines they need to be marked as @Sharable, if not netty refuses to share it.
  @Sharable
  class ThrottlingIpFilter(config: DynamicTLSPeerGroup.IncomingConnectionThrottlingConfig)
      extends AbstractRemoteAddressFilter[InetSocketAddress] {
    private val cache = Caffeine
      .newBuilder()
      .expireAfterWrite(config.throttlingDuration.length, config.throttlingDuration.unit)
      .build[InetAddress, java.lang.Boolean]()
    private val cacheView = cache.asMap()

    private def addIfAbsent(address: InetAddress): Boolean = {
      cacheView.putIfAbsent(address, java.lang.Boolean.TRUE) == null
    }

    override def accept(ctx: ChannelHandlerContext, remoteAddress: InetSocketAddress): Boolean = {
      val address = remoteAddress.getAddress
      val throttleLocalAddress = (address.isLoopbackAddress && !config.throttleLocalhost)

      throttleLocalAddress || addIfAbsent(address)
    }
  }

}
