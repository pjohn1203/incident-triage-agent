package com.example.triage.runtime;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** Turns an incident description into a triage result. The server and evals depend only on this. */
@FunctionalInterface
public interface Triager {

  /**
   * @param incident free-text incident report
   * @param deadline total time budget; work still running when it expires is cancelled
   * @throws TimeoutException if the deadline expires
   */
  TriageResult triage(String incident, Duration deadline) throws TimeoutException;
}
