package io.iohk.network.telemetry

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

trait InMemoryTelemetry extends Telemetery {

  override def registry: MeterRegistry = InMemoryTelemetry.registry
}

object InMemoryTelemetry extends MicrometerRegistryConfig {
  val registry: MeterRegistry = new SimpleMeterRegistry()
}
