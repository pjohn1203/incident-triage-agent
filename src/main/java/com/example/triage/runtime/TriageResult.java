package com.example.triage.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one triage run produced, including what's needed to evaluate and debug it.
 *
 * @param report parsed JSON report, or null if the model's answer wasn't parseable
 * @param rawFinalText the triage lead's final text exactly as returned
 * @param toolCalls every tool call any agent made, in order
 * @param traceId OpenTelemetry trace id, to find the full trace in Jaeger / Cloud Trace
 */
public record TriageResult(
    String model,
    Map<String, Object> report,
    String rawFinalText,
    List<ToolCall> toolCalls,
    long latencyMs,
    int inputTokens,
    int outputTokens,
    String traceId) {

  public record ToolCall(String agent, String tool, Map<String, Object> args) {}

  public List<String> toolNames() {
    return toolCalls.stream().map(ToolCall::tool).toList();
  }

  /** JSON shape returned by the HTTP API. */
  public Map<String, Object> toJson() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("trace_id", traceId);
    out.put("model", model);
    out.put("latency_ms", latencyMs);
    out.put("report", report);
    if (report == null) {
      out.put("raw_text", rawFinalText);
    }
    out.put("tool_calls", toolCalls.stream().map(TriageResult::toolCallJson).toList());
    Map<String, Object> usage = new LinkedHashMap<>();
    usage.put("input_tokens", inputTokens);
    usage.put("output_tokens", outputTokens);
    out.put("usage", usage);
    return out;
  }

  private static Map<String, Object> toolCallJson(ToolCall call) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("agent", call.agent());
    m.put("tool", call.tool());
    m.put("args", call.args());
    return m;
  }
}
