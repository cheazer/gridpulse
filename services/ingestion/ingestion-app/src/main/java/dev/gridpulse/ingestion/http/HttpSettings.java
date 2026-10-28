package dev.gridpulse.ingestion.http;

import java.time.Duration;

/**
 * How we talk to one upstream API: timeouts, retries (ADR-0006) and rate limit (ADR-0007).
 *
 * @param requestsPerSecond sustained rate, including retries
 * @param burst requests allowed back to back before the rate applies
 */
public record HttpSettings(
    Duration connectTimeout,
    Duration readTimeout,
    double requestsPerSecond,
    int burst,
    Retry retry) {

  /**
   * @param maxRetries retries after the first attempt
   * @param initialDelay delay before the first retry; doubles each time up to {@code maxDelay}
   */
  public record Retry(int maxRetries, Duration initialDelay, Duration maxDelay) {}
}
