package com.example.triage.models;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses the model behind each agent. Per-agent overrides let you route cheap, high-volume steps
 * to a small model and the final synthesis to a stronger one.
 */
public final class ModelProvider {

  public static final String BASELINE = "baseline";

  private final String defaultModel;
  private final Map<String, String> perAgent;
  private final long simulatedLatencyMs;
  private final Map<String, BaseLlm> cache = new ConcurrentHashMap<>();

  /**
   * @param defaultModel "baseline" or a model id ADK knows, e.g. "gemini-3.8-flash"
   * @param perAgent agent name to model id overrides
   * @param simulatedLatencyMs per-call delay for the baseline model (0 for none)
   */
  public ModelProvider(String defaultModel, Map<String, String> perAgent, long simulatedLatencyMs) {
    this.defaultModel = defaultModel;
    this.perAgent = Map.copyOf(perAgent);
    this.simulatedLatencyMs = simulatedLatencyMs;
  }

  public static ModelProvider baseline() {
    return new ModelProvider(BASELINE, Map.of(), 0);
  }

  /** The model for {@code agentName}; {@code baselinePolicy} is used only if it's "baseline". */
  public BaseLlm forAgent(String agentName, AgentPolicy baselinePolicy) {
    String model = perAgent.getOrDefault(agentName, defaultModel);
    if (model.equals(BASELINE)) {
      return new BaselineLlm(agentName, baselinePolicy, simulatedLatencyMs);
    }
    // LlmRegistry maps "gemini-*" ids to ADK's Gemini client, which reads GOOGLE_API_KEY (or the
    // Vertex AI settings) from the environment. One client per model id is shared across agents.
    return cache.computeIfAbsent(model, LlmRegistry::getLlm);
  }

  /** Human-readable label for reports and spans, e.g. "gemini-3.8-flash {triage_lead=...}". */
  public String label() {
    return perAgent.isEmpty() ? defaultModel : defaultModel + " " + perAgent;
  }

  public boolean isBaseline() {
    return defaultModel.equals(BASELINE) && perAgent.isEmpty();
  }
}
