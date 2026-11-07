package com.example.triage.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdmissionControllerTest {

  @Test
  void admitsUpToTheLimitThenSheds() throws Exception {
    AdmissionController admission = new AdmissionController(2, Duration.ofMillis(20));
    assertTrue(admission.tryAcquire());
    assertTrue(admission.tryAcquire());
    assertEquals(2, admission.inFlight());

    long start = System.nanoTime();
    assertFalse(admission.tryAcquire());
    assertTrue((System.nanoTime() - start) / 1_000_000 >= 15, "should wait for the queue timeout");

    admission.release();
    assertTrue(admission.tryAcquire());
    assertEquals(2, admission.inFlight());
  }
}
