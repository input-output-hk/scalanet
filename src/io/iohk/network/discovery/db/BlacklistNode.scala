package io.iohk.network.discovery.db

import java.time.Instant

import io.iohk.network.NodeInfo

case class BlacklistNode(nodeInfo: NodeInfo, blacklistSince: Instant, blacklistUntil: Instant)
