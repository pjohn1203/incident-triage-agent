package com.example.triage.heuristics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.triage.data.IncidentData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TriageRulesTest {

  private final IncidentData data = IncidentData.loadDefault();

  private static Map<String, Object> metrics(double p99Base, double p99Now, double errBase, double errNow) {
    return Map.of(
        "status", "ok",
        "p99_latency_ms", Map.of("baseline", p99Base, "current", p99Now),
        "error_rate_pct", Map.of("baseline", errBase, "current", errNow));
  }

  @Test
  void severityRubric() {
    assertEquals("SEV1", TriageRules.severity(metrics(100, 100, 0.1, 5.0)));
    assertEquals("SEV2", TriageRules.severity(metrics(100, 100, 0.1, 1.0)));
    assertEquals("SEV2", TriageRules.severity(metrics(100, 200, 0.1, 0.1)));
    assertEquals("SEV3", TriageRules.severity(metrics(100, 130, 0.1, 0.1)));
    assertEquals("SEV3", TriageRules.severity(metrics(100, 100, 0.25, 0.5)));
    assertEquals("SEV4", TriageRules.severity(metrics(100, 110, 0.1, 0.12)));
    assertEquals("UNKNOWN", TriageRules.severity(Map.of("status", "not_found")));
  }

  private TriageRules.Assessment assess(String service) {
    return TriageRules.assess(
        service,
        data.searchLogs(service, "WARN"),
        data.recentDeploys(service),
        data.getMetrics(service));
  }

  @Test
  void recentDeployWithAnomalyIsTheSuspect() {
    TriageRules.Assessment a = assess("checkout-service");
    assertEquals(TriageRules.Category.BAD_DEPLOY, a.category());
    assertTrue(a.suspectedCause().contains("v2.14.0"));
  }

  @Test
  void deployOutsideCorrelationWindowIsNotBlamed() {
    TriageRules.Assessment a = assess("orders-service");
    assertEquals(TriageRules.Category.DB_POOL, a.category());
    assertTrue(a.suspectedCause().contains("nightly-export"));
  }

  @Test
  void classifiesBySignature() {
    assertEquals(TriageRules.Category.DEPENDENCY, assess("payments-api").category());
    assertEquals(TriageRules.Category.CERT_EXPIRY, assess("auth-service").category());
    assertEquals(TriageRules.Category.MEMORY, assess("search-service").category());
  }

  @Test
  void normalMetricsMeanNoAnomaly() {
    TriageRules.Assessment a = assess("notifications-service");
    assertEquals("SEV4", a.severity());
    assertEquals(TriageRules.Category.NO_ANOMALY, a.category());
  }

  @Test
  void missingTelemetryIsUnknown() {
    TriageRules.Assessment a = assess("billing-service");
    assertEquals("UNKNOWN", a.severity());
    assertEquals(TriageRules.Category.NO_DATA, a.category());
  }

  @Test
  void reportUsesTopRunbookAndAddsPagingForHighSeverity() {
    TriageRules.Assessment a = assess("payments-api");
    Map<String, Object> report =
        TriageRules.report(a, data.lookupRunbook("payments-api", a.category().runbookQuery));
    assertEquals("RB-301", report.get("runbook_id"));
    List<?> steps = (List<?>) report.get("next_steps");
    assertTrue(steps.get(steps.size() - 1).toString().contains("page"));
  }
}
