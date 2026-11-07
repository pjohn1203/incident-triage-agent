package com.example.triage.server;

import com.example.triage.Json;
import com.example.triage.runtime.TriageResult;
import com.example.triage.runtime.Triager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * HTTP front end for the triage pipeline, using only the JDK's built-in server.
 *
 * <pre>
 * POST /v1/triage   {"incident": "..."}  → 200 report | 400 | 413 | 429 | 504 | 500
 * GET  /healthz                           → 200
 * GET  /metrics                           → Prometheus text
 * </pre>
 *
 * <p>Each request runs on a virtual thread, so blocking on the agent run is cheap; the admission
 * controller (not the thread pool) is what bounds concurrency. Incoming W3C {@code traceparent}
 * headers are honoured, so a caller's trace continues through the agents.
 */
public final class TriageServer implements AutoCloseable {

  public record Config(
      int port, int maxConcurrent, Duration maxQueueWait, Duration deadline, int maxIncidentChars) {}

  private static final TextMapGetter<Headers> HEADER_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Headers carrier, String key) {
          return carrier == null ? null : carrier.getFirst(key);
        }
      };

  private final Triager triager;
  private final Config config;
  private final Tracer tracer;
  private final AdmissionController admission;
  private final ServerMetrics metrics;
  private HttpServer server;
  private ExecutorService executor;

  public TriageServer(Triager triager, Config config, Tracer tracer) {
    this.triager = triager;
    this.config = config;
    this.tracer = tracer;
    this.admission = new AdmissionController(config.maxConcurrent(), config.maxQueueWait());
    this.metrics = new ServerMetrics(admission::inFlight);
  }

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(config.port()), 0);
    server.createContext("/v1/triage", this::handleTriage);
    server.createContext("/healthz", ex -> respond(ex, 200, Map.of("status", "ok")));
    server.createContext(
        "/metrics", ex -> respondText(ex, 200, metrics.render(), "text/plain; version=0.0.4"));
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.start();
  }

  /** The bound port (useful when configured with port 0). */
  public int port() {
    return server.getAddress().getPort();
  }

  public ServerMetrics metrics() {
    return metrics;
  }

  @Override
  public void close() {
    if (server != null) {
      server.stop(1);
    }
    if (executor != null) {
      executor.shutdown();
    }
  }

  private void handleTriage(HttpExchange exchange) throws IOException {
    Context parent =
        W3CTraceContextPropagator.getInstance()
            .extract(Context.current(), exchange.getRequestHeaders(), HEADER_GETTER);
    Span span =
        tracer
            .spanBuilder("POST /v1/triage")
            .setSpanKind(SpanKind.SERVER)
            .setParent(parent)
            .startSpan();
    long start = System.nanoTime();
    ServerMetrics.Outcome outcome = ServerMetrics.Outcome.ERROR;
    try (Scope ignored = span.makeCurrent()) {
      exchange.getResponseHeaders().set("X-Trace-Id", span.getSpanContext().getTraceId());
      if (!"POST".equals(exchange.getRequestMethod())) {
        outcome = ServerMetrics.Outcome.BAD_REQUEST;
        respond(exchange, 405, error("Use POST"));
        return;
      }

      String incident;
      try {
        incident = readIncident(exchange.getRequestBody());
      } catch (BadRequestException e) {
        outcome = ServerMetrics.Outcome.BAD_REQUEST;
        respond(exchange, e.status, error(e.getMessage()));
        return;
      }

      boolean admitted;
      try {
        admitted = admission.tryAcquire();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        respond(exchange, 503, error("Shutting down"));
        return;
      }
      if (!admitted) {
        outcome = ServerMetrics.Outcome.REJECTED;
        span.setAttribute("triage.rejected", true);
        exchange.getResponseHeaders().set("Retry-After", "1");
        respond(exchange, 429, error("Too many concurrent requests; retry shortly"));
        return;
      }

      try {
        TriageResult result = triager.triage(incident, config.deadline());
        outcome = ServerMetrics.Outcome.OK;
        respond(exchange, 200, result.toJson());
      } catch (TimeoutException e) {
        outcome = ServerMetrics.Outcome.TIMEOUT;
        span.setStatus(StatusCode.ERROR, "deadline exceeded");
        respond(exchange, 504, error("Triage exceeded the " + config.deadline().toMillis() + "ms deadline"));
      } catch (RuntimeException e) {
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
        respond(exchange, 500, error("Internal error: " + e.getClass().getSimpleName()));
      } finally {
        admission.release();
      }
    } finally {
      long latencyMs = (System.nanoTime() - start) / 1_000_000;
      metrics.record(outcome, latencyMs);
      span.setAttribute("triage.outcome", outcome.name());
      span.end();
      exchange.close();
    }
  }

  private String readIncident(InputStream body) throws IOException, BadRequestException {
    int maxBytes = config.maxIncidentChars() * 4 + 1024;
    byte[] bytes = body.readNBytes(maxBytes + 1);
    if (bytes.length > maxBytes) {
      throw new BadRequestException(413, "Request body too large");
    }
    Map<String, Object> json;
    try {
      json = Json.readMap(new String(bytes, StandardCharsets.UTF_8));
    } catch (JsonProcessingException e) {
      throw new BadRequestException(400, "Body must be JSON like {\"incident\": \"...\"}");
    }
    String incident = Json.asString(json.get("incident")).strip();
    if (incident.isEmpty()) {
      throw new BadRequestException(400, "Field 'incident' is required");
    }
    if (incident.length() > config.maxIncidentChars()) {
      throw new BadRequestException(
          413, "Field 'incident' exceeds " + config.maxIncidentChars() + " characters");
    }
    return incident;
  }

  private static Map<String, Object> error(String message) {
    return Map.of("error", message);
  }

  private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
    respondText(exchange, status, Json.write(body), "application/json");
  }

  private static void respondText(HttpExchange exchange, int status, String body, String type)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static final class BadRequestException extends Exception {
    final int status;

    BadRequestException(int status, String message) {
      super(message);
      this.status = status;
    }
  }
}
