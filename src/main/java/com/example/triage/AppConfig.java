package com.example.triage;

import com.example.triage.agents.TriagePipeline;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime settings. Command-line flags win over environment variables, which win over defaults.
 *
 * <pre>
 * --model / TRIAGE_MODEL                 baseline | gemini-3.8-flash | any model id ADK supports
 * --lead-model / TRIAGE_LEAD_MODEL       optional override for the triage lead only
 * --port / PORT                          8080
 * --max-concurrent / TRIAGE_MAX_CONCURRENT     8
 * --queue-wait-ms / TRIAGE_QUEUE_WAIT_MS       250
 * --deadline-ms / TRIAGE_DEADLINE_MS           60000
 * --max-llm-calls / TRIAGE_MAX_LLM_CALLS       20
 * --simulated-latency-ms / TRIAGE_SIMULATED_LATENCY_MS   0 (baseline model only)
 * </pre>
 */
public record AppConfig(
    String model,
    String leadModel,
    int port,
    int maxConcurrent,
    Duration queueWait,
    Duration deadline,
    int maxLlmCalls,
    long simulatedLatencyMs) {

  public static AppConfig from(Map<String, String> env, Map<String, String> flags) {
    Map<String, String> merged = new HashMap<>();
    merged.put("model", env.getOrDefault("TRIAGE_MODEL", "baseline"));
    merged.put("lead-model", env.getOrDefault("TRIAGE_LEAD_MODEL", ""));
    merged.put("port", env.getOrDefault("PORT", "8080"));
    merged.put("max-concurrent", env.getOrDefault("TRIAGE_MAX_CONCURRENT", "8"));
    merged.put("queue-wait-ms", env.getOrDefault("TRIAGE_QUEUE_WAIT_MS", "250"));
    merged.put("deadline-ms", env.getOrDefault("TRIAGE_DEADLINE_MS", "60000"));
    merged.put("max-llm-calls", env.getOrDefault("TRIAGE_MAX_LLM_CALLS", "20"));
    merged.put("simulated-latency-ms", env.getOrDefault("TRIAGE_SIMULATED_LATENCY_MS", "0"));
    merged.putAll(flags);
    return new AppConfig(
        merged.get("model"),
        merged.get("lead-model"),
        Integer.parseInt(merged.get("port")),
        Integer.parseInt(merged.get("max-concurrent")),
        Duration.ofMillis(Long.parseLong(merged.get("queue-wait-ms"))),
        Duration.ofMillis(Long.parseLong(merged.get("deadline-ms"))),
        Integer.parseInt(merged.get("max-llm-calls")),
        Long.parseLong(merged.get("simulated-latency-ms")));
  }

  /** Per-agent model overrides derived from the settings. */
  public Map<String, String> modelOverrides() {
    return leadModel == null || leadModel.isBlank()
        ? Map.of()
        : Map.of(TriagePipeline.TRIAGE_LEAD, leadModel);
  }
}
