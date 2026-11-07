package com.example.triage.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportParserTest {

  @Test
  void parsesBareJson() {
    assertEquals("SEV1", ReportParser.extractJsonObject("{\"severity\":\"SEV1\"}").orElseThrow().get("severity"));
  }

  @Test
  void parsesFencedJson() {
    String text = "```json\n{\"severity\": \"SEV2\"}\n```";
    assertEquals("SEV2", ReportParser.extractJsonObject(text).orElseThrow().get("severity"));
  }

  @Test
  void parsesJsonWrappedInProse() {
    String text = "Here is the report:\n{\"severity\": \"SEV3\", \"evidence\": [\"a\"]}\nHope it helps!";
    assertEquals("SEV3", ReportParser.extractJsonObject(text).orElseThrow().get("severity"));
  }

  @Test
  void rejectsNonJson() {
    assertTrue(ReportParser.extractJsonObject("no json here").isEmpty());
    assertTrue(ReportParser.extractJsonObject("{not: valid}").isEmpty());
    assertTrue(ReportParser.extractJsonObject(null).isEmpty());
  }

  @Test
  void reportsMissingAndMistypedFields() {
    Map<String, Object> report =
        Map.of(
            "service", "a",
            "severity", "SEV1",
            "suspected_cause", "x",
            "evidence", "not a list",
            "next_steps", java.util.List.of());
    assertEquals(java.util.List.of("evidence", "runbook_id"), ReportParser.missingFields(report));
  }
}
