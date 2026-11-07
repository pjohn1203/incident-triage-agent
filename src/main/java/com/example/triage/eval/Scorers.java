package com.example.triage.eval;

import com.example.triage.Json;
import com.example.triage.runtime.ReportParser;
import com.example.triage.runtime.TriageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic checks for one triage result against its golden case.
 *
 * <p>Each check is narrow on purpose so a failing eval tells you <i>what</i> regressed (format?
 * severity? tool use?) instead of a single opaque score. Subjective qualities, such as whether the
 * next steps are actually useful, need an LLM-as-judge scorer; that's one of the exercises.
 */
public final class Scorers {

  public static final String FORMAT = "format";
  public static final String SERVICE = "service";
  public static final String SEVERITY = "severity";
  public static final String CAUSE = "cause";
  public static final String RUNBOOK = "runbook";
  public static final String TOOLS = "tools";

  public record Check(String name, boolean passed, String detail) {}

  private Scorers() {}

  public static List<Check> score(GoldenCase golden, TriageResult result) {
    GoldenCase.Expected expected = golden.expected();
    Map<String, Object> report = result.report();
    List<Check> checks = new ArrayList<>();

    if (report == null) {
      checks.add(new Check(FORMAT, false, "no JSON object in final answer"));
    } else {
      List<String> missing = ReportParser.missingFields(report);
      checks.add(
          new Check(FORMAT, missing.isEmpty(), missing.isEmpty() ? "ok" : "missing " + missing));
    }
    Map<String, Object> r = report == null ? Map.of() : report;

    String service = Json.asString(r.get("service"));
    checks.add(
        new Check(
            SERVICE,
            service.equalsIgnoreCase(expected.service()),
            "got '" + service + "', want '" + expected.service() + "'"));

    String severity = Json.asString(r.get("severity")).toUpperCase(Locale.ROOT).strip();
    checks.add(
        new Check(
            SEVERITY,
            severity.equals(expected.severity()),
            "got '" + severity + "', want '" + expected.severity() + "'"));

    String cause = Json.asString(r.get("suspected_cause")).toLowerCase(Locale.ROOT);
    boolean causeMatches =
        expected.causeAny().stream().anyMatch(k -> cause.contains(k.toLowerCase(Locale.ROOT)));
    checks.add(
        new Check(
            CAUSE,
            causeMatches,
            causeMatches ? "ok" : "none of " + expected.causeAny() + " in '" + truncate(cause) + "'"));

    if (expected.runbookId() != null) {
      Object runbook = r.get("runbook_id");
      checks.add(
          new Check(
              RUNBOOK,
              Objects.equals(runbook, expected.runbookId()),
              "got '" + runbook + "', want '" + expected.runbookId() + "'"));
    }

    List<String> called = result.toolNames();
    List<String> missingTools =
        expected.requiredTools().stream().filter(t -> !called.contains(t)).toList();
    checks.add(
        new Check(
            TOOLS,
            missingTools.isEmpty(),
            missingTools.isEmpty() ? "called " + called : "never called " + missingTools));
    return checks;
  }

  private static String truncate(String s) {
    return s.length() <= 80 ? s : s.substring(0, 77) + "...";
  }
}
