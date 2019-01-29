package io.iohk.network.telemetry

import com.typesafe.config.ConfigFactory
import io.micrometer.core.instrument.MeterRegistry

trait MicrometerRegistryConfig {
  val registry: MeterRegistry

  protected val configFile = ConfigFactory.load()
  val nodeTag: String = configFile.getString("telemetry.nodeTag")
}
