package com.example.triage.telemetry;

import com.google.adk.telemetry.Tracing;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenTelemetry setup. Spans go to an OTLP/HTTP endpoint (Jaeger, the OpenTelemetry Collector,
 * Google Cloud Trace via the collector, ...) when {@code OTEL_EXPORTER_OTLP_ENDPOINT} is set.
 *
 * <p>Ordering matters: ADK caches its tracer from {@link GlobalOpenTelemetry} when its tracing
 * class loads, and {@code GlobalOpenTelemetry.get()} locks in a no-op if nothing was registered
 * yet. So call {@link #init} first thing in {@code main}. As a safety net, the ADK tracer is also
 * pointed at our SDK explicitly.
 */
public final class Telemetry {

  public static final String INSTRUMENTATION_SCOPE = "com.example.triage";
  private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

  /**
   * @param serviceName {@code service.name} resource attribute
   * @param otlpEndpoint e.g. http://localhost:4318, or null to not export over OTLP
   * @param extraExporters additional exporters, e.g. an in-memory one in tests
   */
  public record Settings(String serviceName, String otlpEndpoint, List<SpanExporter> extraExporters) {

    public static Settings fromEnv(Map<String, String> env) {
      return new Settings(
          env.getOrDefault("OTEL_SERVICE_NAME", "incident-triage"),
          env.get("OTEL_EXPORTER_OTLP_ENDPOINT"),
          List.of());
    }
  }

  private static Telemetry instance;

  private final OpenTelemetrySdk sdk;
  private final String exportTarget;

  private Telemetry(OpenTelemetrySdk sdk, String exportTarget) {
    this.sdk = sdk;
    this.exportTarget = exportTarget;
  }

  /** Initializes tracing once per JVM; later calls return the existing instance. */
  public static synchronized Telemetry init(Settings settings) {
    if (instance != null) {
      return instance;
    }
    Resource resource =
        Resource.getDefault().merge(Resource.create(Attributes.of(SERVICE_NAME, settings.serviceName())));
    SdkTracerProviderBuilder tracerProvider = SdkTracerProvider.builder().setResource(resource);

    String exportTarget = "none";
    if (settings.otlpEndpoint() != null && !settings.otlpEndpoint().isBlank()) {
      String endpoint = tracesUrl(settings.otlpEndpoint());
      tracerProvider.addSpanProcessor(
          BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build())
              .build());
      exportTarget = endpoint;
    }
    for (SpanExporter exporter : settings.extraExporters()) {
      tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(exporter));
    }

    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider.build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    try {
      GlobalOpenTelemetry.set(sdk);
    } catch (IllegalStateException alreadySet) {
      System.getLogger(Telemetry.class.getName())
          .log(
              System.Logger.Level.WARNING,
              "GlobalOpenTelemetry was already set; using this SDK for ADK and app spans only");
    }
    // ADK reads its tracer into a static field when its Tracing class loads. Point it at this SDK
    // explicitly so agent/LLM/tool spans are exported regardless of class-loading order.
    Tracing.setTracerForTesting(sdk.getTracer("gcp.vertex.agent"));

    instance = new Telemetry(sdk, exportTarget);
    return instance;
  }

  /** The initialized instance (initializing with no exporters if needed). */
  public static synchronized Telemetry get() {
    return instance != null ? instance : init(new Settings("incident-triage", null, List.of()));
  }

  public Tracer tracer() {
    return sdk.getTracer(INSTRUMENTATION_SCOPE);
  }

  /** Where spans are being exported, for startup logs. */
  public String exportTarget() {
    return exportTarget;
  }

  /** Flushes buffered spans; call before the process exits. */
  public void shutdown() {
    sdk.shutdown().join(5, TimeUnit.SECONDS);
  }

  /** OTEL_EXPORTER_OTLP_ENDPOINT is a base URL; the traces signal lives at /v1/traces. */
  static String tracesUrl(String endpoint) {
    String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    return base.endsWith("/v1/traces") ? base : base + "/v1/traces";
  }
}
