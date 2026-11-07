package com.example.triage.eval;

import com.example.triage.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One labelled example from {@code evals/golden.jsonl}.
 *
 * @param expected what a correct triage looks like; {@code runbookId} null means "don't check"
 */
public record GoldenCase(String id, String incident, Expected expected) {

  public record Expected(
      String service,
      String severity,
      List<String> causeAny,
      String runbookId,
      List<String> requiredTools) {}

  public static List<GoldenCase> load(Path path) throws IOException {
    List<GoldenCase> cases = new ArrayList<>();
    int lineNumber = 0;
    for (String line : Files.readAllLines(path)) {
      lineNumber++;
      if (line.isBlank() || line.stripLeading().startsWith("//")) {
        continue;
      }
      try {
        cases.add(Json.MAPPER.readValue(line, GoldenCase.class));
      } catch (JsonProcessingException e) {
        throw new IOException(path + ":" + lineNumber + ": " + e.getOriginalMessage(), e);
      }
    }
    return cases;
  }
}
