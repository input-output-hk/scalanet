package io.iohk.network.telemetry
import java.time.Duration

import io.micrometer.core.instrument.binder.{JvmMemoryMetrics, JvmThreadMetrics, ProcessorMetrics}
import io.micrometer.core.instrument.{Clock, MeterRegistry, Tag}
import io.micrometer.datadog.{DatadogConfig, DatadogMeterRegistry}

import collection.JavaConverters._

trait DatadogTelemetry extends Telemetery {

  override def registry: MeterRegistry = DatadogTelemetry.registry

  val tags = List(Tag.of("node", DatadogTelemetry.nodeTag)).asJava

  registry.config().commonTags(tags)
}

object DatadogTelemetry extends MicrometerRegistryConfig {
  self =>

  val step: Duration = configFile.getDuration("telemetry.datadog.duration")

  val apiKey: String = configFile.getString("telemetry.datadog.apiKey")

  val config = new DatadogConfig() {
    override def apiKey(): String = self.apiKey

    override def step(): Duration = self.step

    override def get(key: String): String = null
  }

  override val registry = new DatadogMeterRegistry(config, Clock.SYSTEM)

  new JvmMemoryMetrics().bindTo(registry)
  new ProcessorMetrics().bindTo(registry)
  new JvmThreadMetrics().bindTo(registry)

}
