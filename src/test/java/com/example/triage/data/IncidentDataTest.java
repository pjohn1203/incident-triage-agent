package com.example.triage.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.triage.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IncidentDataTest {

  private final IncidentData data = IncidentData.loadDefault();

  @Test
  void loadsAllFixtureServices() {
    assertEquals(7, data.serviceNames().size());
    assertTrue(data.serviceNames().contains("checkout-service"));
  }

  @Test
  void resolvesCaseInsensitiveNamesAndUniquePrefixes() {
    assertEquals("checkout-service", data.resolveService("Checkout-Service").orElseThrow());
    assertEquals("checkout-service", data.resolveService("checkout").orElseThrow());
    assertTrue(data.resolveService("billing-service").isEmpty());
  }

  @Test
  void searchLogsReturnsErrorsFirstAndRespectsLevel() {
    Map<String, Object> result = data.searchLogs("payments-api", "WARN");
    List<Object> signatures = Json.asList(result.get("signatures"));
    assertEquals("ok", result.get("status"));
    assertEquals(3, signatures.size());
    assertEquals("ERROR", Json.asMap(signatures.get(0)).get("level"));
    assertEquals(1840, Json.asMap(signatures.get(0)).get("count"));

    List<Object> errorsOnly = Json.asList(data.searchLogs("payments-api", "error").get("signatures"));
    assertEquals(2, errorsOnly.size());
  }

  @Test
  void recentDeploysOnlyIncludesTheLookBackWindow() {
    List<Object> deploys = Json.asList(data.recentDeploys("checkout-service").get("deploys"));
    assertEquals(1, deploys.size());
    assertEquals("v2.14.0", Json.asMap(deploys.get(0)).get("version"));
    assertTrue(Json.asList(data.recentDeploys("payments-api").get("deploys")).isEmpty());
  }

  @Test
  void metricsFlagAnomalies() {
    Map<String, Object> checkout = data.getMetrics("checkout-service");
    assertEquals(5.6, Json.path(checkout, "p99_latency_ms", "ratio"));
    assertFalse(Json.asList(checkout.get("anomalies")).isEmpty());
    assertTrue(Json.asList(data.getMetrics("notifications-service").get("anomalies")).isEmpty());
  }

  @Test
  void unknownServicesReturnNotFoundWithSuggestions() {
    Map<String, Object> result = data.getMetrics("billing-service");
    assertEquals("not_found", result.get("status"));
    assertEquals(7, Json.asList(result.get("known_services")).size());
  }

  @Test
  void runbookLookupRanksByKeywordOverlap() {
    Map<String, Object> result = data.lookupRunbook("orders-service", "database connection pool exhaustion");
    Map<String, Object> best = Json.asMap(Json.asList(result.get("candidates")).get(0));
    assertEquals("RB-201", best.get("id"));
    assertEquals("no_match", data.lookupRunbook("x", "zebra").get("status"));
  }
}
