package com.example.triage.models;

import java.util.Map;

/** One model turn: either call a tool or answer with text. */
public sealed interface Decision permits Decision.CallTool, Decision.Respond {

  record CallTool(String tool, Map<String, Object> args) implements Decision {}

  record Respond(String text) implements Decision {}
}
