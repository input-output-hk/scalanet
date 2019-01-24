package io.iohk.network.utils

import org.slf4j.LoggerFactory
trait Logger {
  val log = LoggerFactory.getLogger(getClass)
}
