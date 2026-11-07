package com.example.triage.models;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a scripted policy sees on each model call: the incident text from the user turn, the
 * agent's system instruction (after ADK filled in session-state placeholders), and the results of
 * tools this agent already called.
 */
public record PolicyInput(String incident, String systemInstruction, List<ToolResult> toolResults) {

  public record ToolResult(String name, Map<String, Object> response) {}

  public Optional<ToolResult> lastResult(String toolName) {
    for (int i = toolResults.size() - 1; i >= 0; i--) {
      if (toolResults.get(i).name().equals(toolName)) {
        return Optional.of(toolResults.get(i));
      }
    }
    return Optional.empty();
  }
}
