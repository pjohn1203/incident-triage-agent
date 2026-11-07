package com.example.triage.eval;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Aggregated eval results: per-case outcomes, per-check pass rates, latency and token totals. */
public record EvalReport(String model, List<CaseResult> cases) {

  /**
   * @param error set when the run itself failed (timeout, exception), in which case checks is empty
   */
  public record CaseResult(
      String id,
      boolean passed,
      List<Scorers.Check> checks,
      long latencyMs,
      int inputTokens,
      int outputTokens,
      String traceId,
      String error,
      Map<String, Object> report) {}

  public double passRate() {
    return cases.isEmpty() ? 0 : (double) cases.stream().filter(CaseResult::passed).count() / cases.size();
  }

  /** Pass rate per check name, e.g. {"severity": 0.9, "tools": 1.0}. */
  public Map<String, Double> checkPassRates() {
    Map<String, int[]> tally = new TreeMap<>();
    for (CaseResult c : cases) {
      for (Scorers.Check check : c.checks()) {
        int[] t = tally.computeIfAbsent(check.name(), k -> new int[2]);
        t[1]++;
        if (check.passed()) {
          t[0]++;
        }
      }
    }
    Map<String, Double> rates = new TreeMap<>();
    tally.forEach((name, t) -> rates.put(name, (double) t[0] / t[1]));
    return rates;
  }

  public long latencyPercentile(double p) {
    List<Long> sorted = cases.stream().map(CaseResult::latencyMs).sorted().toList();
    if (sorted.isEmpty()) {
      return 0;
    }
    int rank = (int) Math.ceil(p / 100.0 * sorted.size());
    return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
  }

  public Map<String, Object> toJson() {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("model", model);
    summary.put("cases", cases.size());
    summary.put("pass_rate", round(passRate()));
    Map<String, Double> rates = new LinkedHashMap<>();
    checkPassRates().forEach((k, v) -> rates.put(k, round(v)));
    summary.put("check_pass_rates", rates);
    summary.put("latency_ms_p50", latencyPercentile(50));
    summary.put("latency_ms_p95", latencyPercentile(95));
    summary.put("input_tokens", cases.stream().mapToInt(CaseResult::inputTokens).sum());
    summary.put("output_tokens", cases.stream().mapToInt(CaseResult::outputTokens).sum());

    List<Map<String, Object>> caseJson = new ArrayList<>();
    for (CaseResult c : cases) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", c.id());
      m.put("passed", c.passed());
      m.put("latency_ms", c.latencyMs());
      m.put("trace_id", c.traceId());
      if (c.error() != null) {
        m.put("error", c.error());
      }
      Map<String, Object> checks = new LinkedHashMap<>();
      for (Scorers.Check check : c.checks()) {
        checks.put(check.name(), Map.of("passed", check.passed(), "detail", check.detail()));
      }
      m.put("checks", checks);
      m.put("report", c.report());
      caseJson.add(m);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("summary", summary);
    out.put("cases", caseJson);
    return out;
  }

  public void print(PrintStream out) {
    out.printf(Locale.ROOT, "%nEval results for model: %s%n", model);
    out.println("-".repeat(78));
    for (CaseResult c : cases) {
      String failures =
          c.error() != null
              ? c.error()
              : String.join(
                  "; ",
                  c.checks().stream()
                      .filter(ch -> !ch.passed())
                      .map(ch -> ch.name() + ": " + ch.detail())
                      .toList());
      out.printf(
          Locale.ROOT,
          "%-4s %-32s %6dms  %s%n",
          c.passed() ? "PASS" : "FAIL",
          c.id(),
          c.latencyMs(),
          failures);
    }
    out.println("-".repeat(78));
    out.printf(
        Locale.ROOT,
        "pass rate %.0f%%  |  p50 %dms  p95 %dms  |  tokens in %d / out %d%n",
        passRate() * 100,
        latencyPercentile(50),
        latencyPercentile(95),
        cases.stream().mapToInt(CaseResult::inputTokens).sum(),
        cases.stream().mapToInt(CaseResult::outputTokens).sum());
    StringBuilder perCheck = new StringBuilder("per check:");
    checkPassRates()
        .forEach((k, v) -> perCheck.append(String.format(Locale.ROOT, "  %s %.0f%%", k, v * 100)));
    out.println(perCheck);
  }

  private static double round(double v) {
    return Math.round(v * 1000) / 1000.0;
  }
}
