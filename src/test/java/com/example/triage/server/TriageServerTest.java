package com.example.triage.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.triage.TestTelemetry;
import com.example.triage.runtime.TriageResult;
import com.example.triage.runtime.Triager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class TriageServerTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static TriageResult fakeResult() {
    return new TriageResult(
        "fake", Map.of("severity", "SEV2"), "{}", List.of(), 5, 10, 20, "trace-123");
  }

  private static TriageServer start(Triager triager, int maxConcurrent) throws Exception {
    TriageServer server =
        new TriageServer(
            triager,
            new TriageServer.Config(
                0, maxConcurrent, Duration.ofMillis(10), Duration.ofSeconds(5), 100),
            TestTelemetry.tracer());
    server.start();
    return server;
  }

  private static HttpRequest.Builder request(TriageServer server, String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path));
  }

  private static HttpResponse<String> post(TriageServer server, String body) throws Exception {
    return HTTP.send(
        request(server, "/v1/triage").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void returnsTheReportWithATraceId() throws Exception {
    try (TriageServer server = start((incident, deadline) -> fakeResult(), 2)) {
      HttpResponse<String> response = post(server, "{\"incident\": \"checkout is slow\"}");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"severity\":\"SEV2\""), response.body());
      assertTrue(response.headers().firstValue("X-Trace-Id").isPresent());
    }
  }

  @Test
  void validatesInput() throws Exception {
    try (TriageServer server = start((incident, deadline) -> fakeResult(), 2)) {
      assertEquals(400, post(server, "not json").statusCode());
      assertEquals(400, post(server, "{}").statusCode());
      assertEquals(413, post(server, "{\"incident\": \"" + "x".repeat(101) + "\"}").statusCode());
      HttpResponse<String> get =
          HTTP.send(request(server, "/v1/triage").GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(405, get.statusCode());
      assertEquals(4, server.metrics().count(ServerMetrics.Outcome.BAD_REQUEST));
    }
  }

  @Test
  void shedsLoadWith429WhenSaturated() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Triager slow =
        (incident, deadline) -> {
          entered.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return fakeResult();
        };
    try (TriageServer server = start(slow, 1)) {
      CompletableFuture<HttpResponse<String>> first =
          HTTP.sendAsync(
              request(server, "/v1/triage")
                  .POST(HttpRequest.BodyPublishers.ofString("{\"incident\": \"one\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertTrue(entered.await(5, TimeUnit.SECONDS));

      HttpResponse<String> second = post(server, "{\"incident\": \"two\"}");
      assertEquals(429, second.statusCode());
      assertEquals("1", second.headers().firstValue("Retry-After").orElse(""));

      release.countDown();
      assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
      assertEquals(1, server.metrics().count(ServerMetrics.Outcome.REJECTED));
    }
  }

  @Test
  void mapsDeadlineExpiryTo504() throws Exception {
    Triager timesOut =
        (incident, deadline) -> {
          throw new TimeoutException("too slow");
        };
    try (TriageServer server = start(timesOut, 2)) {
      assertEquals(504, post(server, "{\"incident\": \"x\"}").statusCode());
      assertEquals(1, server.metrics().count(ServerMetrics.Outcome.TIMEOUT));
    }
  }

  @Test
  void servesHealthAndPrometheusMetrics() throws Exception {
    try (TriageServer server = start((incident, deadline) -> fakeResult(), 2)) {
      post(server, "{\"incident\": \"x\"}");
      HttpResponse<String> health =
          HTTP.send(request(server, "/healthz").GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, health.statusCode());
      HttpResponse<String> metrics =
          HTTP.send(request(server, "/metrics").GET().build(), HttpResponse.BodyHandlers.ofString());
      assertTrue(metrics.body().contains("triage_requests_total{outcome=\"ok\"} 1"), metrics.body());
      assertTrue(metrics.body().contains("triage_latency_ms_count 1"), metrics.body());
    }
  }
}
