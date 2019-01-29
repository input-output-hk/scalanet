package io.iohk.network.telemetry

import io.micrometer.core.instrument.MeterRegistry

trait Telemetery {

  def registry: MeterRegistry
}
