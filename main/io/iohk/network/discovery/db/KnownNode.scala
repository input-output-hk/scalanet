package io.iohk.network.discovery.db

import java.time.Instant

import io.iohk.network.NodeInfo

case class KnownNode(node: NodeInfo, discovered: Instant, lastSeen: Instant)
