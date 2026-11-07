package com.example.triage.models;

/** Decides an agent's next move. The baseline model is just one policy per agent. */
@FunctionalInterface
public interface AgentPolicy {
  Decision decide(PolicyInput input);
}
