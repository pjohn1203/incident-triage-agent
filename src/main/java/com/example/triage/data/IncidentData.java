package com.example.triage.data;

import com.example.triage.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-memory stand-in for the observability stack: logs, deploy history, metrics and runbooks.
 *
 * <p>Every query returns a small, already-summarised map instead of raw data. That is deliberate
 * tool design for LLM agents: compact results keep prompts short (cheaper, faster) and make the
 * model's job easier. Swap this class for real backends (Cloud Logging, Prometheus, your deploy
 * system) without touching the agents.
 */
public final class IncidentData {

  /** How far back {@link #recentDeploys} looks. */
  public static final int DEPLOY_WINDOW_MINUTES = 180;

  private static final List<String> LEVELS = List.of("INFO", "WARN", "ERROR");

  public record Metric(double baseline, double current) {
    public double ratio() {
      return baseline <= 0 ? 0 : current / baseline;
    }
  }

  public record Metrics(Metric p99LatencyMs, Metric errorRatePct, Metric requestsPerSec) {}

  public record LogSignature(String level, String message, int count) {}

  public record Deploy(String version, int minutesBeforeAlert, String author, String summary) {}

  public record ServiceTelemetry(
      String owner, Metrics metrics, List<LogSignature> logs, List<Deploy> deploys) {}

  public record Runbook(String id, String title, List<String> keywords, List<String> steps) {}

  private final Map<String, ServiceTelemetry> services;
  private final List<Runbook> runbooks;

  public IncidentData(Map<String, ServiceTelemetry> services, List<Runbook> runbooks) {
    this.services = new TreeMap<>(services);
    this.runbooks = List.copyOf(runbooks);
  }

  /** Loads the bundled fixtures from {@code src/main/resources/fixtures}. */
  public static IncidentData loadDefault() {
    try {
      JsonNode telemetry = readResource("/fixtures/telemetry.json");
      JsonNode runbookRoot = readResource("/fixtures/runbooks.json");
      Map<String, ServiceTelemetry> services = new TreeMap<>();
      for (Map.Entry<String, JsonNode> entry : telemetry.get("services").properties()) {
        services.put(
            entry.getKey(), Json.MAPPER.treeToValue(entry.getValue(), ServiceTelemetry.class));
      }
      List<Runbook> runbooks = new ArrayList<>();
      for (JsonNode node : runbookRoot.get("runbooks")) {
        runbooks.add(Json.MAPPER.treeToValue(node, Runbook.class));
      }
      return new IncidentData(services, runbooks);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not load fixtures", e);
    }
  }

  private static JsonNode readResource(String path) throws IOException {
    try (InputStream in = IncidentData.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("Missing classpath resource " + path);
      }
      return Json.MAPPER.readTree(in);
    }
  }

  public Set<String> serviceNames() {
    return services.keySet();
  }

  /**
   * Resolves a service name the way a forgiving API would: case-insensitive, and a unique prefix
   * such as "checkout" resolves to "checkout-service".
   */
  public Optional<String> resolveService(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    String wanted = name.trim().toLowerCase(Locale.ROOT);
    if (services.containsKey(wanted)) {
      return Optional.of(wanted);
    }
    List<String> prefixMatches =
        services.keySet().stream().filter(s -> s.startsWith(wanted + "-")).toList();
    return prefixMatches.size() == 1 ? Optional.of(prefixMatches.get(0)) : Optional.empty();
  }

  // ---------------------------------------------------------------------------------------------
  // Queries backing the agent tools. Each returns a JSON-friendly map with a "status" field.
  // ---------------------------------------------------------------------------------------------

  /** Top log signatures at or above {@code minLevel} (default WARN), most frequent first. */
  public Map<String, Object> searchLogs(String service, String minLevel) {
    Optional<String> resolved = resolveService(service);
    if (resolved.isEmpty()) {
      return notFound(service);
    }
    String level = normalizeLevel(minLevel);
    int threshold = LEVELS.indexOf(level);
    List<Map<String, Object>> signatures =
        services.get(resolved.get()).logs().stream()
            .filter(log -> LEVELS.indexOf(log.level()) >= threshold)
            .sorted(
                Comparator.comparingInt((LogSignature log) -> LEVELS.indexOf(log.level()))
                    .reversed()
                    .thenComparing(Comparator.comparingInt(LogSignature::count).reversed()))
            .limit(5)
            .map(
                log -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("level", log.level());
                  m.put("message", log.message());
                  m.put("count", log.count());
                  return m;
                })
            .toList();
    Map<String, Object> out = ok(resolved.get());
    out.put("min_level", level);
    out.put("signatures", signatures);
    return out;
  }

  /** Deploys that shipped within {@link #DEPLOY_WINDOW_MINUTES} before the alert, newest first. */
  public Map<String, Object> recentDeploys(String service) {
    Optional<String> resolved = resolveService(service);
    if (resolved.isEmpty()) {
      return notFound(service);
    }
    List<Map<String, Object>> deploys =
        services.get(resolved.get()).deploys().stream()
            .filter(d -> d.minutesBeforeAlert() <= DEPLOY_WINDOW_MINUTES)
            .sorted(Comparator.comparingInt(Deploy::minutesBeforeAlert))
            .map(
                d -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("version", d.version());
                  m.put("minutes_before_alert", d.minutesBeforeAlert());
                  m.put("summary", d.summary());
                  return m;
                })
            .toList();
    Map<String, Object> out = ok(resolved.get());
    out.put("window_minutes", DEPLOY_WINDOW_MINUTES);
    out.put("deploys", deploys);
    return out;
  }

  /** Current vs. baseline golden signals, with simple anomaly annotations. */
  public Map<String, Object> getMetrics(String service) {
    Optional<String> resolved = resolveService(service);
    if (resolved.isEmpty()) {
      return notFound(service);
    }
    Metrics m = services.get(resolved.get()).metrics();
    List<String> anomalies = new ArrayList<>();
    if (m.p99LatencyMs().ratio() >= 1.3) {
      anomalies.add(
          String.format(
              Locale.ROOT, "p99 latency is %.1fx baseline", m.p99LatencyMs().ratio()));
    }
    double err = m.errorRatePct().current();
    if (err >= 1.0 || (err >= 0.5 && m.errorRatePct().ratio() >= 2.0)) {
      anomalies.add(
          String.format(
              Locale.ROOT,
              "error rate %.2f%% vs %.2f%% baseline",
              err,
              m.errorRatePct().baseline()));
    }
    Map<String, Object> out = ok(resolved.get());
    out.put("owner", services.get(resolved.get()).owner());
    out.put("p99_latency_ms", metric(m.p99LatencyMs()));
    out.put("error_rate_pct", metric(m.errorRatePct()));
    out.put("requests_per_sec", metric(m.requestsPerSec()));
    out.put("anomalies", anomalies);
    return out;
  }

  /**
   * Keyword search over runbooks. Returns up to three candidates so the calling agent can choose;
   * a good first exercise is replacing this with embedding-based semantic search.
   */
  public Map<String, Object> lookupRunbook(String service, String symptom) {
    String query = symptom == null ? "" : symptom.toLowerCase(Locale.ROOT);
    record Scored(Runbook runbook, int score) {}
    List<Map<String, Object>> candidates =
        runbooks.stream()
            .map(rb -> new Scored(rb, (int) rb.keywords().stream().filter(query::contains).count()))
            .filter(s -> s.score() > 0)
            .sorted(Comparator.comparingInt(Scored::score).reversed())
            .limit(3)
            .map(
                s -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("id", s.runbook().id());
                  m.put("title", s.runbook().title());
                  m.put("score", s.score());
                  m.put("steps", s.runbook().steps());
                  return m;
                })
            .toList();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", candidates.isEmpty() ? "no_match" : "ok");
    out.put("service", service);
    out.put("query", symptom);
    out.put("candidates", candidates);
    return out;
  }

  private Map<String, Object> notFound(String service) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", "not_found");
    out.put("service", service);
    out.put("message", "No telemetry for this service. Known services are listed below.");
    out.put("known_services", List.copyOf(services.keySet()));
    return out;
  }

  private static Map<String, Object> ok(String service) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", "ok");
    out.put("service", service);
    return out;
  }

  private static Map<String, Object> metric(Metric metric) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("baseline", metric.baseline());
    m.put("current", metric.current());
    m.put("ratio", Math.round(metric.ratio() * 10) / 10.0);
    return m;
  }

  private static String normalizeLevel(String level) {
    String upper = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
    if (upper.equals("WARNING")) {
      upper = "WARN";
    }
    return LEVELS.contains(upper) ? upper : "WARN";
  }
}
