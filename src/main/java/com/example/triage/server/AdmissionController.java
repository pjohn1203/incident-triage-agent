package com.example.triage.server;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded concurrency with a short queue wait, i.e. load shedding.
 *
 * <p>Model calls are slow and rate-limited upstream. Letting every request in just builds an
 * unbounded queue where everything eventually times out. Capping in-flight work and failing fast
 * with 429 keeps latency predictable for the requests that are admitted, and tells clients to
 * back off.
 */
public final class AdmissionController {

  private final Semaphore permits;
  private final Duration maxQueueWait;
  private final AtomicInteger inFlight = new AtomicInteger();

  public AdmissionController(int maxConcurrent, Duration maxQueueWait) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException("maxConcurrent must be >= 1");
    }
    this.permits = new Semaphore(maxConcurrent, /* fair= */ true);
    this.maxQueueWait = maxQueueWait;
  }

  /** Waits up to {@code maxQueueWait} for a slot. Every {@code true} must be paired with release. */
  public boolean tryAcquire() throws InterruptedException {
    boolean admitted = permits.tryAcquire(maxQueueWait.toMillis(), TimeUnit.MILLISECONDS);
    if (admitted) {
      inFlight.incrementAndGet();
    }
    return admitted;
  }

  public void release() {
    inFlight.decrementAndGet();
    permits.release();
  }

  public int inFlight() {
    return inFlight.get();
  }
}
