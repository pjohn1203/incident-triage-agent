package com.example.triage.agents;

import com.example.triage.data.IncidentData;
import com.example.triage.models.AgentPolicy;
import com.example.triage.models.BaselinePolicies;
import com.example.triage.models.ModelProvider;
import com.example.triage.tools.TriageTools;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import java.util.Map;
import java.util.Set;

/**
 * The agent graph:
 *
 * <pre>
 * incident_triage (SequentialAgent)
 * ├── evidence_gathering (ParallelAgent)      three analysts run concurrently
 * │   ├── log_analyst      → search_logs     → state["logs_findings"]
 * │   ├── change_analyst   → recent_deploys  → state["change_findings"]
 * │   └── metrics_analyst  → get_metrics     → state["metrics_findings"]
 * └── triage_lead          → lookup_runbook  → state["triage_report"]  (final JSON report)
 * </pre>
 *
 * <p>Control flow is deterministic (workflow agents decide who runs when); the LLMs decide only
 * what to call and what to say. Agent-to-agent transfer is disabled for the same reason: fewer
 * places for the model to wander off.
 *
 * <p>Sampling settings are left at the model defaults on purpose: Google recommends keeping
 * Gemini 3 models at temperature 1.0 (lower values can cause looping). Repeatability for CI comes
 * from the baseline model; for real models, measure run-to-run variance in the evals instead.
 */
public final class TriagePipeline {

  public static final String ROOT = "incident_triage";
  public static final String EVIDENCE = "evidence_gathering";
  public static final String LOG_ANALYST = "log_analyst";
  public static final String CHANGE_ANALYST = "change_analyst";
  public static final String METRICS_ANALYST = "metrics_analyst";
  public static final String TRIAGE_LEAD = "triage_lead";

  public static final String LOGS_KEY = "logs_findings";
  public static final String CHANGES_KEY = "change_findings";
  public static final String METRICS_KEY = "metrics_findings";
  public static final String REPORT_KEY = "triage_report";

  private TriagePipeline() {}

  /** The scripted policy the baseline model uses for each agent. */
  public static AgentPolicy baselinePolicy(String agentName, Set<String> knownServices) {
    return switch (agentName) {
      case LOG_ANALYST ->
          BaselinePolicies.analyst(
              TriageTools.SEARCH_LOGS,
              service -> Map.of("service", service, "min_level", "WARN"),
              knownServices);
      case CHANGE_ANALYST ->
          BaselinePolicies.analyst(
              TriageTools.RECENT_DEPLOYS, service -> Map.of("service", service), knownServices);
      case METRICS_ANALYST ->
          BaselinePolicies.analyst(
              TriageTools.GET_METRICS, service -> Map.of("service", service), knownServices);
      case TRIAGE_LEAD ->
          BaselinePolicies.lead(knownServices, LOGS_KEY, CHANGES_KEY, METRICS_KEY);
      default -> throw new IllegalArgumentException("No baseline policy for " + agentName);
    };
  }

  public static BaseAgent build(IncidentData data, ModelProvider models) {
    TriageTools tools = new TriageTools(data);
    Set<String> known = data.serviceNames();

    LlmAgent logAnalyst =
        LlmAgent.builder()
            .name(LOG_ANALYST)
            .description("Finds the dominant error signatures in the service's logs.")
            .model(models.forAgent(LOG_ANALYST, baselinePolicy(LOG_ANALYST, known)))
            .instruction(Prompts.LOG_ANALYST)
            .tools(tools.searchLogsTool())
            .outputKey(LOGS_KEY)
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();

    LlmAgent changeAnalyst =
        LlmAgent.builder()
            .name(CHANGE_ANALYST)
            .description("Lists recent deploys that could have caused the incident.")
            .model(models.forAgent(CHANGE_ANALYST, baselinePolicy(CHANGE_ANALYST, known)))
            .instruction(Prompts.CHANGE_ANALYST)
            .tools(tools.recentDeploysTool())
            .outputKey(CHANGES_KEY)
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();

    LlmAgent metricsAnalyst =
        LlmAgent.builder()
            .name(METRICS_ANALYST)
            .description("Compares current golden signals with their baseline.")
            .model(models.forAgent(METRICS_ANALYST, baselinePolicy(METRICS_ANALYST, known)))
            .instruction(Prompts.METRICS_ANALYST)
            .tools(tools.getMetricsTool())
            .outputKey(METRICS_KEY)
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();

    ParallelAgent evidence =
        ParallelAgent.builder()
            .name(EVIDENCE)
            .description("Gathers logs, change history and metrics concurrently.")
            .subAgents(logAnalyst, changeAnalyst, metricsAnalyst)
            .build();

    LlmAgent lead =
        LlmAgent.builder()
            .name(TRIAGE_LEAD)
            .description("Synthesises the findings into a severity, cause and next steps.")
            .model(models.forAgent(TRIAGE_LEAD, baselinePolicy(TRIAGE_LEAD, known)))
            .instruction(Prompts.TRIAGE_LEAD)
            .tools(tools.lookupRunbookTool())
            .outputKey(REPORT_KEY)
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();

    return SequentialAgent.builder()
        .name(ROOT)
        .description("Triage an incident: gather evidence in parallel, then synthesise a report.")
        .subAgents(evidence, lead)
        .build();
  }
}
