package com.example.triage.models;

import com.example.triage.Json;
import com.example.triage.heuristics.ServiceExtractor;
import com.example.triage.heuristics.TriageRules;
import com.example.triage.runtime.ReportParser;
import com.example.triage.tools.TriageTools;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scripted policies that make the baseline model behave like a (very literal) agent team. */
public final class BaselinePolicies {

  private BaselinePolicies() {}

  /**
   * Evidence agent: call {@code tool} once for the service named in the incident, then report the
   * tool result as the finding.
   */
  public static AgentPolicy analyst(
      String tool,
      Function<String, Map<String, Object>> argsForService,
      Collection<String> knownServices) {
    return input -> {
      Optional<PolicyInput.ToolResult> result = input.lastResult(tool);
      if (result.isEmpty()) {
        String service =
            ServiceExtractor.extract(input.incident(), knownServices).orElse("unknown");
        return new Decision.CallTool(tool, new LinkedHashMap<>(argsForService.apply(service)));
      }
      return new Decision.Respond(Json.write(result.get().response()));
    };
  }

  /**
   * Triage lead: read the findings that ADK templated into the system instruction, apply {@link
   * TriageRules}, look up a runbook, then answer with the JSON report.
   *
   * @param findingKeys session-state keys of the findings, which the lead prompt wraps in
   *     {@code <key>...</key>} tags, in order: logs, changes, metrics
   */
  public static AgentPolicy lead(Collection<String> knownServices, String... findingKeys) {
    if (findingKeys.length != 3) {
      throw new IllegalArgumentException("Expected logs, changes and metrics finding keys");
    }
    return input -> {
      Map<String, Object> logs = finding(input.systemInstruction(), findingKeys[0]);
      Map<String, Object> changes = finding(input.systemInstruction(), findingKeys[1]);
      Map<String, Object> metrics = finding(input.systemInstruction(), findingKeys[2]);
      String service =
          firstNonBlank(Json.asString(metrics.get("service")), Json.asString(logs.get("service")))
              .or(() -> ServiceExtractor.extract(input.incident(), knownServices))
              .orElse("unknown");
      TriageRules.Assessment assessment = TriageRules.assess(service, logs, changes, metrics);

      Optional<PolicyInput.ToolResult> runbooks = input.lastResult(TriageTools.LOOKUP_RUNBOOK);
      if (runbooks.isEmpty()) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("service", service);
        args.put("symptom", assessment.category().runbookQuery);
        return new Decision.CallTool(TriageTools.LOOKUP_RUNBOOK, args);
      }
      return new Decision.Respond(
          Json.write(TriageRules.report(assessment, runbooks.get().response())));
    };
  }

  /** Extracts the JSON object between {@code <key>} and {@code </key>} in the instruction. */
  static Map<String, Object> finding(String systemInstruction, String key) {
    Matcher m =
        Pattern.compile(
                "<" + Pattern.quote(key) + ">(.*?)</" + Pattern.quote(key) + ">", Pattern.DOTALL)
            .matcher(systemInstruction);
    if (!m.find()) {
      return Map.of();
    }
    return ReportParser.extractJsonObject(m.group(1)).orElse(Map.of());
  }

  private static Optional<String> firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return Optional.of(v);
      }
    }
    return Optional.empty();
  }
}
