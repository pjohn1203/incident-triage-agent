package com.example.triage.tools;

import com.example.triage.data.IncidentData;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import java.util.Map;

/**
 * The functions agents can call. ADK turns each annotated method into a tool declaration (name,
 * description, JSON schema for the parameters) that the model sees.
 *
 * <p>Descriptions are part of the prompt: they tell the model when and how to call the tool, so
 * they are worth writing as carefully as the agent instructions.
 */
public final class TriageTools {

  public static final String SEARCH_LOGS = "search_logs";
  public static final String RECENT_DEPLOYS = "recent_deploys";
  public static final String GET_METRICS = "get_metrics";
  public static final String LOOKUP_RUNBOOK = "lookup_runbook";

  private final IncidentData data;

  public TriageTools(IncidentData data) {
    this.data = data;
  }

  @Schema(
      name = SEARCH_LOGS,
      description =
          "Returns the most frequent log message signatures for a service, highest severity first.")
  public Map<String, Object> searchLogs(
      @Schema(name = "service", description = "Service name, e.g. checkout-service") String service,
      @Schema(
              name = "min_level",
              description = "Lowest log level to include: ERROR, WARN or INFO. Defaults to WARN.",
              optional = true)
          String minLevel) {
    return data.searchLogs(service, minLevel);
  }

  @Schema(
      name = RECENT_DEPLOYS,
      description =
          "Lists deploys of a service in the 180 minutes before the alert, newest first, with"
              + " how many minutes before the alert each one shipped.")
  public Map<String, Object> recentDeploys(
      @Schema(name = "service", description = "Service name, e.g. checkout-service")
          String service) {
    return data.recentDeploys(service);
  }

  @Schema(
      name = GET_METRICS,
      description =
          "Returns current vs. baseline p99 latency, error rate and throughput for a service,"
              + " plus detected anomalies.")
  public Map<String, Object> getMetrics(
      @Schema(name = "service", description = "Service name, e.g. checkout-service")
          String service) {
    return data.getMetrics(service);
  }

  @Schema(
      name = LOOKUP_RUNBOOK,
      description =
          "Searches the runbook catalog and returns up to three candidate runbooks with steps.")
  public Map<String, Object> lookupRunbook(
      @Schema(name = "service", description = "Service name, e.g. checkout-service") String service,
      @Schema(
              name = "symptom",
              description =
                  "Short description of the suspected problem, e.g. 'bad deploy regression' or"
                      + " 'database connection pool exhaustion'")
          String symptom) {
    return data.lookupRunbook(service, symptom);
  }

  public FunctionTool searchLogsTool() {
    return FunctionTool.create(this, "searchLogs");
  }

  public FunctionTool recentDeploysTool() {
    return FunctionTool.create(this, "recentDeploys");
  }

  public FunctionTool getMetricsTool() {
    return FunctionTool.create(this, "getMetrics");
  }

  public FunctionTool lookupRunbookTool() {
    return FunctionTool.create(this, "lookupRunbook");
  }
}
