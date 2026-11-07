package com.example.triage.server;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/**
 * Request counters and a latency histogram, rendered in Prometheus text format at /metrics.
 *
 * <p>Hand-rolled to keep dependencies small; in a real service you would use the OpenTelemetry
 * metrics SDK or Micrometer (good exercise).
 */
public final class ServerMetrics {

  public enum Outcome {
    OK,
    BAD_REQUEST,
    REJECTED,
    TIMEOUT,
    ERROR
  }

  private static final long[] BUCKETS_MS = {50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000, 30_000};

  private final Map<Outcome, LongAdder> requests = new ConcurrentHashMap<>();
  private final LongAdder[] bucketCounts = new LongAdder[BUCKETS_MS.length];
  private final LongAdder latencyCount = new LongAdder();
  private final LongAdder latencySumMs = new LongAdder();
  private final IntSupplier inFlight;

  public ServerMetrics(IntSupplier inFlight) {
    this.inFlight = inFlight;
    for (int i = 0; i < bucketCounts.length; i++) {
      bucketCounts[i] = new LongAdder();
    }
  }

  public void record(Outcome outcome, long latencyMs) {
    requests.computeIfAbsent(outcome, o -> new LongAdder()).increment();
    if (outcome == Outcome.OK) {
      latencyCount.increment();
      latencySumMs.add(latencyMs);
      for (int i = 0; i < BUCKETS_MS.length; i++) {
        if (latencyMs <= BUCKETS_MS[i]) {
          bucketCounts[i].increment();
          break;
        }
      }
    }
  }

  public long count(Outcome outcome) {
    LongAdder adder = requests.get(outcome);
    return adder == null ? 0 : adder.sum();
  }

  public String render() {
    StringBuilder sb = new StringBuilder();
    sb.append("# HELP triage_requests_total Triage requests by outcome.\n");
    sb.append("# TYPE triage_requests_total counter\n");
    Map<Outcome, LongAdder> sorted = new TreeMap<>(requests);
    for (Outcome outcome : Outcome.values()) {
      long value = sorted.containsKey(outcome) ? sorted.get(outcome).sum() : 0;
      sb.append(
          String.format(
              Locale.ROOT,
              "triage_requests_total{outcome=\"%s\"} %d%n",
              outcome.name().toLowerCase(Locale.ROOT),
              value));
    }
    sb.append("# HELP triage_inflight Requests currently being processed.\n");
    sb.append("# TYPE triage_inflight gauge\n");
    sb.append("triage_inflight ").append(inFlight.getAsInt()).append('\n');
    sb.append("# HELP triage_latency_ms Latency of successful triage requests.\n");
    sb.append("# TYPE triage_latency_ms histogram\n");
    long cumulative = 0;
    for (int i = 0; i < BUCKETS_MS.length; i++) {
      cumulative += bucketCounts[i].sum();
      sb.append(
          String.format(
              Locale.ROOT, "triage_latency_ms_bucket{le=\"%d\"} %d%n", BUCKETS_MS[i], cumulative));
    }
    sb.append(
        String.format(Locale.ROOT, "triage_latency_ms_bucket{le=\"+Inf\"} %d%n", latencyCount.sum()));
    sb.append("triage_latency_ms_sum ").append(latencySumMs.sum()).append('\n');
    sb.append("triage_latency_ms_count ").append(latencyCount.sum()).append('\n');
    return sb.toString();
  }
}
