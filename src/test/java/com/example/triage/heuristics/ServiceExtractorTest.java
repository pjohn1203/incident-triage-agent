package com.example.triage.heuristics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ServiceExtractorTest {

  private static final Set<String> KNOWN = Set.of("checkout-service", "payments-api", "orders-service");

  @Test
  void prefersKnownServiceNames() {
    assertEquals(
        "payments-api",
        ServiceExtractor.extract("payments-api errors after checkout-ish traffic", KNOWN).orElseThrow());
  }

  @Test
  void findsUnknownServiceShapedNames() {
    assertEquals(
        "billing-service", ServiceExtractor.extract("billing-service is down", KNOWN).orElseThrow());
  }

  @Test
  void fallsBackToShortNames() {
    assertEquals(
        "checkout-service",
        ServiceExtractor.extract("Customers say checkout is slow", KNOWN).orElseThrow());
  }

  @Test
  void returnsEmptyWhenNothingMatches() {
    assertTrue(ServiceExtractor.extract("everything is on fire", KNOWN).isEmpty());
  }
}
