package com.example.triage.runtime;

import com.example.triage.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pulls a JSON object out of model text. Models are asked for bare JSON but regularly wrap it in
 * markdown fences or add a sentence before it; production code has to tolerate that (and the eval
 * suite measures how often it happens via the "format" check).
 */
public final class ReportParser {

  /** Fields every triage report must have for the "format" check to pass. */
  public static final List<String> REQUIRED_FIELDS =
      List.of("service", "severity", "suspected_cause", "evidence", "next_steps", "runbook_id");

  private ReportParser() {}

  public static Optional<Map<String, Object>> extractJsonObject(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String s = text.strip();
    if (s.startsWith("```")) {
      int firstNewline = s.indexOf('\n');
      s = firstNewline < 0 ? "" : s.substring(firstNewline + 1);
      int fence = s.lastIndexOf("```");
      if (fence >= 0) {
        s = s.substring(0, fence);
      }
    }
    int start = s.indexOf('{');
    int end = s.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return Optional.empty();
    }
    try {
      return Optional.of(Json.readMap(s.substring(start, end + 1)));
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }

  /** Returns the required fields that are missing (or of the wrong type) in {@code report}. */
  public static List<String> missingFields(Map<String, Object> report) {
    return REQUIRED_FIELDS.stream()
        .filter(
            field -> {
              if (!report.containsKey(field)) {
                return true;
              }
              Object value = report.get(field);
              return switch (field) {
                case "evidence", "next_steps" -> !(value instanceof List<?>);
                case "runbook_id" -> value != null && !(value instanceof String);
                default -> !(value instanceof String s) || s.isBlank();
              };
            })
        .toList();
  }
}
