package io.iohk.scalanet.peergroup.dynamictls

import com.github.benmanes.caffeine.cache.Caffeine
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.ipfilter.AbstractRemoteAddressFilter

import java.net.{InetAddress, InetSocketAddress}

private[scalanet] object CustomHandlers {

  /**
    *
    * Custom handler which keeps recent history of incoming connections. If it receive new connection from the ip address
    * which is still in history, it rejects it as it means the remote caller tries to often.
    *
    * Handler needs to be thread safe as it is shared between several netty pipelines
    *
    * To share handlers between pipelines they need to be marked as @Sharable, if not netty refuses to share it.
    */
  @Sharable
  class ThrottlingIpFilter(config: DynamicTLSPeerGroup.IncomingConnectionThrottlingConfig)
      extends AbstractRemoteAddressFilter[InetSocketAddress] {

    private val cacheView = Caffeine
      .newBuilder()
      .expireAfterWrite(config.throttlingDuration.length, config.throttlingDuration.unit)
      .build[InetAddress, java.lang.Boolean]()
      .asMap()

    private def isQuotaAvailable(address: InetAddress): Boolean = {
      cacheView.putIfAbsent(address, java.lang.Boolean.TRUE) == null
    }

    override def accept(ctx: ChannelHandlerContext, remoteAddress: InetSocketAddress): Boolean = {
      val address = remoteAddress.getAddress
      val localNoThrottle = (address.isLoopbackAddress && !config.throttleLocalhost)

      localNoThrottle || isQuotaAvailable(address)
    }
  }

}
