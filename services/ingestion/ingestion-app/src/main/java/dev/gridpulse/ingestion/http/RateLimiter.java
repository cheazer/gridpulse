package dev.gridpulse.ingestion.http;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Token-bucket rate limiter (ADR-0007): on average {@code permitsPerSecond}, with up to {@code
 * burst} requests allowed back to back. Callers block in {@link #acquire()} until their slot; with
 * virtual threads that costs nothing.
 *
 * <p>Implemented as a "theoretical arrival time" (GCRA): one timestamp instead of a token count and
 * refill thread.
 */
public final class RateLimiter {

  /** Sleeps for a duration; replaceable in tests. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final long nanosPerPermit;
  private final long burstAllowanceNanos;
  private final LongSupplier nanoTime;
  private final Sleeper sleeper;
  private long theoreticalArrival;
  private boolean started;

  public RateLimiter(double permitsPerSecond, int burst) {
    this(permitsPerSecond, burst, System::nanoTime, d -> Thread.sleep(d));
  }

  RateLimiter(double permitsPerSecond, int burst, LongSupplier nanoTime, Sleeper sleeper) {
    if (permitsPerSecond <= 0 || burst < 1) {
      throw new IllegalArgumentException("need permitsPerSecond > 0 and burst >= 1");
    }
    this.nanosPerPermit = (long) (1_000_000_000L / permitsPerSecond);
    this.burstAllowanceNanos = (burst - 1) * nanosPerPermit;
    this.nanoTime = nanoTime;
    this.sleeper = sleeper;
  }

  /** Blocks until a request may be made. */
  public void acquire() {
    long waitNanos;
    synchronized (this) {
      long now = nanoTime.getAsLong();
      if (!started || theoreticalArrival < now) {
        theoreticalArrival = now;
        started = true;
      }
      waitNanos = Math.max(0, theoreticalArrival - burstAllowanceNanos - now);
      theoreticalArrival += nanosPerPermit;
    }
    if (waitNanos > 0) {
      try {
        sleeper.sleep(Duration.ofNanos(waitNanos));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for the rate limit", e);
      }
    }
  }
}
