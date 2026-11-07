package com.example.triage;

import com.example.triage.telemetry.Telemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.List;

/**
 * Shared test telemetry. OpenTelemetry's global instance can be set once per JVM, so every test
 * that needs tracing goes through here and filters spans by trace id.
 */
public final class TestTelemetry {

  private static InMemorySpanExporter exporter;

  private TestTelemetry() {}

  public static synchronized InMemorySpanExporter exporter() {
    if (exporter == null) {
      exporter = InMemorySpanExporter.create();
      Telemetry.init(new Telemetry.Settings("incident-triage-test", null, List.of(exporter)));
    }
    return exporter;
  }

  public static Tracer tracer() {
    exporter();
    return Telemetry.get().tracer();
  }
}
