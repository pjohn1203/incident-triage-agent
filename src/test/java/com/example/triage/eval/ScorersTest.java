package com.example.triage.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.triage.runtime.TriageResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScorersTest {

  private static final GoldenCase CASE =
      new GoldenCase(
          "c1",
          "checkout-service is slow",
          new GoldenCase.Expected(
              "checkout-service",
              "SEV2",
              List.of("v2.14.0"),
              "RB-101",
              List.of("search_logs", "lookup_runbook")));

  private static Map<String, Object> goodReport() {
    Map<String, Object> report = new HashMap<>();
    report.put("service", "checkout-service");
    report.put("severity", "SEV2");
    report.put("suspected_cause", "Regression from deploy v2.14.0");
    report.put("evidence", List.of("p99 5.6x"));
    report.put("next_steps", List.of("Roll back"));
    report.put("runbook_id", "RB-101");
    return report;
  }

  private static TriageResult result(Map<String, Object> report, List<String> tools) {
    return new TriageResult(
        "test",
        report,
        "",
        tools.stream().map(t -> new TriageResult.ToolCall("agent", t, Map.of())).toList(),
        1,
        0,
        0,
        "t");
  }

  private static Map<String, Boolean> outcomes(List<Scorers.Check> checks) {
    Map<String, Boolean> out = new HashMap<>();
    checks.forEach(c -> out.put(c.name(), c.passed()));
    return out;
  }

  @Test
  void allChecksPassForACorrectReport() {
    List<Scorers.Check> checks =
        Scorers.score(CASE, result(goodReport(), List.of("search_logs", "lookup_runbook")));
    assertTrue(checks.stream().allMatch(Scorers.Check::passed), checks.toString());
    assertEquals(6, checks.size());
  }

  @Test
  void eachCheckFailsIndependently() {
    Map<String, Object> report = goodReport();
    report.put("severity", "SEV4");
    report.put("runbook_id", "RB-201");
    Map<String, Boolean> outcome = outcomes(Scorers.score(CASE, result(report, List.of("search_logs"))));
    assertFalse(outcome.get(Scorers.SEVERITY));
    assertFalse(outcome.get(Scorers.RUNBOOK));
    assertFalse(outcome.get(Scorers.TOOLS));
    assertTrue(outcome.get(Scorers.SERVICE));
    assertTrue(outcome.get(Scorers.CAUSE));
  }

  @Test
  void unparseableAnswerFailsFormat() {
    Map<String, Boolean> outcome = outcomes(Scorers.score(CASE, result(null, List.of())));
    assertFalse(outcome.get(Scorers.FORMAT));
    assertFalse(outcome.get(Scorers.SEVERITY));
  }

  @Test
  void nullRunbookExpectationSkipsTheCheck() {
    GoldenCase noRunbook =
        new GoldenCase(
            "c2",
            "x",
            new GoldenCase.Expected("checkout-service", "SEV2", List.of("v2.14.0"), null, List.of()));
    assertFalse(outcomes(Scorers.score(noRunbook, result(goodReport(), List.of()))).containsKey(Scorers.RUNBOOK));
  }
}
