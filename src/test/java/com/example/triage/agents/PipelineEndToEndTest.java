package com.example.triage.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.triage.TestTelemetry;
import com.example.triage.data.IncidentData;
import com.example.triage.eval.GoldenCase;
import com.example.triage.eval.Scorers;
import com.example.triage.models.ModelProvider;
import com.example.triage.runtime.AdkTriager;
import com.example.triage.runtime.TriageResult;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs the real ADK runtime (agents, parallel workflow, function tools, state templating) with the
 * baseline model, and checks results and traces.
 */
class PipelineEndToEndTest {

  private static InMemorySpanExporter spans;
  private static AdkTriager triager;

  @BeforeAll
  static void setUp() {
    spans = TestTelemetry.exporter(); // before any ADK class loads
    triager =
        new AdkTriager(
            TriagePipeline.build(IncidentData.loadDefault(), ModelProvider.baseline()),
            TriagePipeline.TRIAGE_LEAD,
            "baseline",
            TestTelemetry.tracer(),
            20);
  }

  @Test
  void baselinePassesEveryGoldenCaseThroughAdk() throws Exception {
    for (GoldenCase golden : GoldenCase.load(Path.of("evals/golden.jsonl"))) {
      TriageResult result = triager.triage(golden.incident(), Duration.ofSeconds(10));
      List<Scorers.Check> failed =
          Scorers.score(golden, result).stream().filter(c -> !c.passed()).toList();
      assertTrue(failed.isEmpty(), golden.id() + " failed " + failed + "\n" + result.rawFinalText());
    }
  }

  @Test
  void runsEachAnalystOnceThenTheLead() throws Exception {
    TriageResult result = triager.triage("checkout-service p99 is high", Duration.ofSeconds(10));
    Map<String, List<String>> toolsByAgent =
        result.toolCalls().stream()
            .collect(
                Collectors.groupingBy(
                    TriageResult.ToolCall::agent,
                    Collectors.mapping(TriageResult.ToolCall::tool, Collectors.toList())));
    assertEquals(List.of("search_logs"), toolsByAgent.get(TriagePipeline.LOG_ANALYST));
    assertEquals(List.of("recent_deploys"), toolsByAgent.get(TriagePipeline.CHANGE_ANALYST));
    assertEquals(List.of("get_metrics"), toolsByAgent.get(TriagePipeline.METRICS_ANALYST));
    assertEquals(List.of("lookup_runbook"), toolsByAgent.get(TriagePipeline.TRIAGE_LEAD));
  }

  @Test
  void emitsOneTraceCoveringAgentsAndTools() throws Exception {
    TriageResult result = triager.triage("payments-api is failing", Duration.ofSeconds(10));
    assertNotNull(result.traceId());

    Set<String> expected =
        Set.of(
            "triage",
            "invocation",
            "invoke_agent " + TriagePipeline.TRIAGE_LEAD,
            "execute_tool lookup_runbook",
            "call_llm");
    Set<String> names = Set.of();
    // ADK ends some spans asynchronously as its reactive streams complete; give them a moment.
    for (int i = 0; i < 40 && !names.containsAll(expected); i++) {
      names =
          spans.getFinishedSpanItems().stream()
              .filter(s -> s.getTraceId().equals(result.traceId()))
              .map(SpanData::getName)
              .collect(Collectors.toSet());
      Thread.sleep(50);
    }
    assertTrue(names.containsAll(expected), "spans in trace: " + names);
  }

  @Test
  void enforcesTheDeadline() {
    AdkTriager slow =
        new AdkTriager(
            TriagePipeline.build(
                IncidentData.loadDefault(), new ModelProvider(ModelProvider.BASELINE, Map.of(), 200)),
            TriagePipeline.TRIAGE_LEAD,
            "baseline-slow",
            TestTelemetry.tracer(),
            20);
    assertThrows(TimeoutException.class, () -> slow.triage("checkout-service", Duration.ofMillis(100)));
  }
}
