package com.example.triage.eval;

import com.example.triage.runtime.TriageResult;
import com.example.triage.runtime.Triager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * Runs every golden case through a {@link Triager} and scores it.
 *
 * <p>Keep parallelism low against real models: rate limits (429s) show up as failures that have
 * nothing to do with quality.
 */
public final class EvalRunner {

  private EvalRunner() {}

  public static EvalReport run(
      Triager triager, String model, List<GoldenCase> cases, Duration deadline, int parallelism)
      throws InterruptedException {
    List<EvalReport.CaseResult> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallelism))) {
      List<Future<EvalReport.CaseResult>> futures = new ArrayList<>();
      for (GoldenCase golden : cases) {
        futures.add(pool.submit(() -> runOne(triager, golden, deadline)));
      }
      for (Future<EvalReport.CaseResult> future : futures) {
        try {
          results.add(future.get());
        } catch (ExecutionException e) {
          throw new IllegalStateException("Eval case crashed", e.getCause());
        }
      }
    }
    return new EvalReport(model, results);
  }

  static EvalReport.CaseResult runOne(Triager triager, GoldenCase golden, Duration deadline) {
    long start = System.nanoTime();
    try {
      TriageResult result = triager.triage(golden.incident(), deadline);
      List<Scorers.Check> checks = Scorers.score(golden, result);
      boolean passed = checks.stream().allMatch(Scorers.Check::passed);
      return new EvalReport.CaseResult(
          golden.id(),
          passed,
          checks,
          result.latencyMs(),
          result.inputTokens(),
          result.outputTokens(),
          result.traceId(),
          null,
          result.report());
    } catch (TimeoutException e) {
      return failed(golden, start, "timeout after " + deadline.toMillis() + "ms");
    } catch (RuntimeException e) {
      return failed(golden, start, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static EvalReport.CaseResult failed(GoldenCase golden, long startNanos, String error) {
    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
    return new EvalReport.CaseResult(
        golden.id(), false, List.of(), latencyMs, 0, 0, null, error, null);
  }
}
