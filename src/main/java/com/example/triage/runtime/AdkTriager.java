package com.example.triage.runtime;

import com.example.triage.Json;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the agent graph for one incident with ADK's {@link Runner}.
 *
 * <p>Production concerns handled here:
 *
 * <ul>
 *   <li><b>Deadline</b>: the whole run is bounded; on expiry the reactive stream is disposed,
 *       which cancels in-flight model and tool calls.
 *   <li><b>Runaway protection</b>: {@code maxLlmCalls} caps model calls per run, so a confused
 *       agent can't loop (and spend) forever.
 *   <li><b>Session hygiene</b>: each request gets a fresh session that is deleted afterwards;
 *       in-memory sessions otherwise accumulate for the life of the process.
 *   <li><b>Tracing</b>: a {@code triage} span wraps the run. ADK's own spans (invocation,
 *       invoke_agent, call_llm, execute_tool) nest under it because it is the current context
 *       when the run is subscribed.
 * </ul>
 */
public final class AdkTriager implements Triager {

  public static final String APP_NAME = "incident-triage";
  private static final String USER_ID = "oncall";

  private final Runner runner;
  private final String reportingAgent;
  private final String modelLabel;
  private final Tracer tracer;
  private final RunConfig runConfig;

  /**
   * @param root the agent graph
   * @param reportingAgent name of the agent whose final answer is the report
   * @param modelLabel shown in results and spans
   * @param maxLlmCalls upper bound on model calls per run
   */
  public AdkTriager(
      BaseAgent root, String reportingAgent, String modelLabel, Tracer tracer, int maxLlmCalls) {
    this.runner = new InMemoryRunner(root, APP_NAME);
    this.reportingAgent = reportingAgent;
    this.modelLabel = modelLabel;
    this.tracer = tracer;
    this.runConfig = RunConfig.builder().maxLlmCalls(maxLlmCalls).build();
  }

  public String modelLabel() {
    return modelLabel;
  }

  @Override
  public TriageResult triage(String incident, Duration deadline) throws TimeoutException {
    Span span =
        tracer
            .spanBuilder("triage")
            .setAttribute("triage.model", modelLabel)
            .setAttribute("triage.incident.chars", (long) incident.length())
            .startSpan();
    long startNanos = System.nanoTime();
    String sessionId = null;
    try (Scope ignored = span.makeCurrent()) {
      sessionId = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet().id();
      Content message = Content.fromParts(Part.fromText(incident));
      List<Event> events =
          runner
              .runAsync(USER_ID, sessionId, message, runConfig)
              .toList()
              .timeout(deadline.toMillis(), TimeUnit.MILLISECONDS)
              .blockingGet();

      long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
      TriageResult result = summarize(events, latencyMs, span.getSpanContext().getTraceId());
      span.setAttribute("triage.tool_calls", (long) result.toolCalls().size());
      span.setAttribute("triage.report_parsed", result.report() != null);
      span.setAttribute("gen_ai.usage.input_tokens", (long) result.inputTokens());
      span.setAttribute("gen_ai.usage.output_tokens", (long) result.outputTokens());
      if (result.report() != null) {
        span.setAttribute("triage.severity", Json.asString(result.report().get("severity")));
      }
      return result;
    } catch (RuntimeException e) {
      // RxJava's blockingGet wraps checked exceptions such as TimeoutException.
      if (e.getCause() instanceof TimeoutException timeout) {
        span.setStatus(StatusCode.ERROR, "deadline exceeded");
        throw timeout;
      }
      span.recordException(e);
      span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
      throw e;
    } finally {
      span.end();
      if (sessionId != null) {
        deleteQuietly(sessionId);
      }
    }
  }

  private TriageResult summarize(List<Event> events, long latencyMs, String traceId) {
    List<TriageResult.ToolCall> toolCalls = new ArrayList<>();
    String finalText = "";
    String modelError = null;
    int inputTokens = 0;
    int outputTokens = 0;

    for (Event event : events) {
      for (FunctionCall call : event.functionCalls()) {
        toolCalls.add(
            new TriageResult.ToolCall(
                event.author(), call.name().orElse("?"), call.args().orElse(Map.of())));
      }
      if (event.usageMetadata().isPresent()) {
        GenerateContentResponseUsageMetadata usage = event.usageMetadata().get();
        inputTokens += usage.promptTokenCount().orElse(0);
        outputTokens += usage.candidatesTokenCount().orElse(0);
      }
      if (event.errorMessage().isPresent()) {
        modelError = event.author() + ": " + event.errorMessage().get();
      }
      if (event.author().equals(reportingAgent) && event.finalResponse()) {
        String text = textOf(event);
        if (!text.isBlank()) {
          finalText = text;
        }
      }
    }
    if (finalText.isBlank() && modelError != null) {
      finalText = "[model error] " + modelError;
    }
    Map<String, Object> report = ReportParser.extractJsonObject(finalText).orElse(null);
    return new TriageResult(
        modelLabel,
        report,
        finalText,
        toolCalls,
        latencyMs,
        inputTokens,
        outputTokens,
        traceId);
  }

  private static String textOf(Event event) {
    StringBuilder sb = new StringBuilder();
    for (Part part : event.content().flatMap(Content::parts).orElse(List.of())) {
      if (!part.thought().orElse(false)) {
        part.text().ifPresent(sb::append);
      }
    }
    return sb.toString();
  }

  private void deleteQuietly(String sessionId) {
    try {
      runner.sessionService().deleteSession(APP_NAME, USER_ID, sessionId).blockingAwait();
    } catch (RuntimeException e) {
      System.getLogger(AdkTriager.class.getName())
          .log(System.Logger.Level.WARNING, "Could not delete session " + sessionId, e);
    }
  }
}
