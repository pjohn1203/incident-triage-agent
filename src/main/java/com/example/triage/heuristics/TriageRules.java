package com.example.triage.heuristics;

import com.example.triage.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A rules-based triager: the "baseline model" the LLM pipeline is measured against.
 *
 * <p>Comparing an LLM system with a cheap deterministic baseline is standard eval practice. If the
 * LLM can't beat (or at least match) a page of if-statements on your golden set, that tells you
 * something. The same severity rubric is written into the triage lead's prompt.
 *
 * <p>Inputs are the loosely typed maps the evidence agents produce (same shape as the tool
 * results in {@link com.example.triage.data.IncidentData}).
 */
public final class TriageRules {

  /** A deploy this close to the alert is treated as a suspect. */
  public static final int DEPLOY_CORRELATION_MINUTES = 60;

  public enum Category {
    BAD_DEPLOY("bad deploy regression rollback"),
    CERT_EXPIRY("expired tls certificate"),
    MEMORY("out of memory heap gc"),
    DB_POOL("database connection pool exhaustion"),
    DEPENDENCY("upstream dependency timeout"),
    NO_ANOMALY("no anomaly alert noise"),
    UNCLASSIFIED("elevated errors"),
    NO_DATA("no telemetry data unknown service");

    /** What the baseline sends to lookup_runbook for this category. */
    public final String runbookQuery;

    Category(String runbookQuery) {
      this.runbookQuery = runbookQuery;
    }
  }

  public record Assessment(
      String service,
      String severity,
      Category category,
      String suspectedCause,
      List<String> evidence,
      String confidence) {}

  private TriageRules() {}

  /**
   * Severity rubric. SEV1: error rate >= 5%. SEV2: error rate >= 1% or p99 >= 2x baseline. SEV3:
   * p99 >= 1.3x baseline, or error rate doubled and >= 0.5%. SEV4: otherwise. UNKNOWN: no metrics.
   */
  public static String severity(Map<String, Object> metrics) {
    Optional<Double> errNow = Json.asNumber(Json.path(metrics, "error_rate_pct", "current"));
    Optional<Double> errBase = Json.asNumber(Json.path(metrics, "error_rate_pct", "baseline"));
    Optional<Double> p99Now = Json.asNumber(Json.path(metrics, "p99_latency_ms", "current"));
    Optional<Double> p99Base = Json.asNumber(Json.path(metrics, "p99_latency_ms", "baseline"));
    if (!"ok".equals(metrics.get("status")) || errNow.isEmpty() || p99Now.isEmpty()) {
      return "UNKNOWN";
    }
    double err = errNow.get();
    double latencyRatio = p99Base.filter(b -> b > 0).map(b -> p99Now.get() / b).orElse(1.0);
    double errorRatio = errBase.filter(b -> b > 0).map(b -> err / b).orElse(1.0);
    if (err >= 5.0) {
      return "SEV1";
    }
    if (err >= 1.0 || latencyRatio >= 2.0) {
      return "SEV2";
    }
    if (latencyRatio >= 1.3 || (err >= 0.5 && errorRatio >= 2.0)) {
      return "SEV3";
    }
    return "SEV4";
  }

  public static Assessment assess(
      String service,
      Map<String, Object> logs,
      Map<String, Object> changes,
      Map<String, Object> metrics) {
    if (!"ok".equals(metrics.get("status")) && !"ok".equals(logs.get("status"))) {
      return new Assessment(
          service,
          "UNKNOWN",
          Category.NO_DATA,
          "No telemetry found for " + service + "; verify the service name or escalate to the"
              + " owning team",
          List.of("Logs, deploys and metrics lookups returned not_found for " + service),
          "low");
    }

    String severity = severity(metrics);
    boolean anomalous = !severity.equals("SEV4") && !severity.equals("UNKNOWN");
    List<Map<String, Object>> signatures = signatures(logs);
    Optional<Map<String, Object>> topError =
        signatures.stream().filter(s -> "ERROR".equals(s.get("level"))).findFirst();
    Optional<Map<String, Object>> topSignal =
        topError.isPresent() ? topError : signatures.stream().findFirst();
    Optional<Map<String, Object>> suspectDeploy =
        deploys(changes).stream()
            .filter(
                d ->
                    Json.asNumber(d.get("minutes_before_alert")).orElse(Double.MAX_VALUE)
                        <= DEPLOY_CORRELATION_MINUTES)
            .min(
                Comparator.comparingDouble(
                    d -> Json.asNumber(d.get("minutes_before_alert")).orElse(Double.MAX_VALUE)));

    String allSignals =
        String.join(
                " | ",
                signatures.stream().map(s -> Json.asString(s.get("message"))).toList())
            .toLowerCase(Locale.ROOT);
    String topMessage = topSignal.map(s -> Json.asString(s.get("message"))).orElse("");

    Category category;
    String cause;
    if (!anomalous) {
      category = Category.NO_ANOMALY;
      cause =
          "No significant anomaly: latency and error rate are within the normal range of"
              + " baseline; likely alert noise";
    } else if (suspectDeploy.isPresent()) {
      Map<String, Object> d = suspectDeploy.get();
      category = Category.BAD_DEPLOY;
      cause =
          String.format(
              Locale.ROOT,
              "Regression from deploy %s (shipped %d min before the alert: %s); top error since:"
                  + " \"%s\"",
              d.get("version"),
              Json.asNumber(d.get("minutes_before_alert")).orElse(0.0).intValue(),
              d.get("summary"),
              topMessage);
    } else if (containsAny(allSignals, "certificate", "x509")) {
      category = Category.CERT_EXPIRY;
      cause = "Expired TLS certificate: \"" + topMessage + "\"";
    } else if (containsAny(allSignals, "outofmemory", "heap space", "gc pause", "oomkilled")) {
      category = Category.MEMORY;
      cause = "Memory pressure / heap exhaustion: \"" + topMessage + "\"";
    } else if (containsAny(allSignals, "connection is not available", "connection pool", "hikari")) {
      category = Category.DB_POOL;
      cause = "Database connection pool exhaustion: \"" + topMessage + "\"";
      Optional<String> holder =
          signatures.stream()
              .map(s -> Json.asString(s.get("message")))
              .filter(msg -> msg.toLowerCase(Locale.ROOT).contains("job="))
              .findFirst();
      if (holder.isPresent()) {
        cause += "; connections likely held by: \"" + holder.get() + "\"";
      }
    } else if (containsAny(allSignals, "upstream", "circuit breaker", "timeout calling")) {
      category = Category.DEPENDENCY;
      cause = "Upstream dependency failure: \"" + topMessage + "\"";
    } else {
      category = Category.UNCLASSIFIED;
      cause =
          topMessage.isEmpty()
              ? "Anomalous metrics without a matching log signature"
              : "Elevated errors without a known signature: \"" + topMessage + "\"";
    }

    List<String> evidence = new ArrayList<>();
    metricsEvidence(metrics).ifPresent(evidence::add);
    topSignal.ifPresent(
        s ->
            evidence.add(
                String.format(
                    Locale.ROOT,
                    "Top log signature (%s): \"%s\" x%d",
                    s.get("level"),
                    s.get("message"),
                    Json.asNumber(s.get("count")).orElse(0.0).intValue())));
    evidence.add(deployEvidence(changes, suspectDeploy));

    String confidence =
        switch (category) {
          case NO_ANOMALY -> "medium";
          case UNCLASSIFIED, NO_DATA -> "low";
          default -> severity.equals("SEV3") ? "medium" : "high";
        };
    return new Assessment(service, severity, category, cause, evidence, confidence);
  }

  /** Builds the final report the triage lead returns, using the best runbook candidate. */
  public static Map<String, Object> report(Assessment a, Map<String, Object> runbookResult) {
    Map<String, Object> runbook =
        Json.asList(runbookResult.get("candidates")).stream()
            .map(Json::asMap)
            .findFirst()
            .orElse(Map.of());
    List<String> nextSteps = new ArrayList<>();
    Json.asList(runbook.get("steps")).stream().limit(3).map(Json::asString).forEach(nextSteps::add);
    if (a.severity().equals("SEV1") || a.severity().equals("SEV2")) {
      nextSteps.add("Open an incident channel and page the owning team");
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("service", a.service());
    report.put("severity", a.severity());
    report.put("category", a.category().name());
    report.put("suspected_cause", a.suspectedCause());
    report.put("evidence", a.evidence());
    report.put("next_steps", nextSteps);
    report.put("runbook_id", runbook.isEmpty() ? null : runbook.get("id"));
    report.put("confidence", a.confidence());
    return report;
  }

  private static List<Map<String, Object>> signatures(Map<String, Object> logs) {
    return Json.asList(logs.get("signatures")).stream().map(Json::asMap).toList();
  }

  private static List<Map<String, Object>> deploys(Map<String, Object> changes) {
    return Json.asList(changes.get("deploys")).stream().map(Json::asMap).toList();
  }

  private static Optional<String> metricsEvidence(Map<String, Object> metrics) {
    Optional<Double> p99Now = Json.asNumber(Json.path(metrics, "p99_latency_ms", "current"));
    Optional<Double> p99Base = Json.asNumber(Json.path(metrics, "p99_latency_ms", "baseline"));
    Optional<Double> errNow = Json.asNumber(Json.path(metrics, "error_rate_pct", "current"));
    Optional<Double> errBase = Json.asNumber(Json.path(metrics, "error_rate_pct", "baseline"));
    if (p99Now.isEmpty() || errNow.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        String.format(
            Locale.ROOT,
            "p99 latency %.0fms vs %.0fms baseline; error rate %.2f%% vs %.2f%% baseline",
            p99Now.get(),
            p99Base.orElse(0.0),
            errNow.get(),
            errBase.orElse(0.0)));
  }

  private static String deployEvidence(
      Map<String, Object> changes, Optional<Map<String, Object>> suspect) {
    if (suspect.isPresent()) {
      Map<String, Object> d = suspect.get();
      return String.format(
          Locale.ROOT,
          "Deploy %s shipped %d min before the alert: %s",
          d.get("version"),
          Json.asNumber(d.get("minutes_before_alert")).orElse(0.0).intValue(),
          d.get("summary"));
    }
    List<Map<String, Object>> deploys = deploys(changes);
    if (deploys.isEmpty()) {
      return "No deploys in the look-back window";
    }
    Map<String, Object> latest = deploys.get(0);
    return String.format(
        Locale.ROOT,
        "Latest deploy %s was %d min before the alert, outside the %d-minute correlation window",
        latest.get("version"),
        Json.asNumber(latest.get("minutes_before_alert")).orElse(0.0).intValue(),
        DEPLOY_CORRELATION_MINUTES);
  }

  private static boolean containsAny(String haystack, String... needles) {
    for (String needle : needles) {
      if (haystack.contains(needle)) {
        return true;
      }
    }
    return false;
  }
}
