package dev.gridpulse.ingestion.http;

import java.time.Duration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Retry rules shared by every upstream API client (ADR-0006): retry what might succeed next time
 * (network errors, timeouts, 5xx, 429) with exponential backoff and jitter; fail fast on other 4xx,
 * which mean our request is wrong.
 */
public final class HttpRetries {

  private HttpRetries() {}

  /**
   * @param maxRetries retries after the first attempt
   * @param initialDelay delay before the first retry; doubles on each retry up to {@code maxDelay}
   */
  public static RetryTemplate template(int maxRetries, Duration initialDelay, Duration maxDelay) {
    return new RetryTemplate(
        RetryPolicy.builder()
            .maxRetries(maxRetries)
            .delay(initialDelay)
            .multiplier(2)
            .maxDelay(maxDelay)
            // Jitter spreads retries out, so many clients failing together don't retry in lockstep.
            .jitter(initialDelay.dividedBy(2))
            .predicate(HttpRetries::isTransient)
            .build());
  }

  static boolean isTransient(Throwable error) {
    return error instanceof ResourceAccessException
        || error instanceof HttpServerErrorException
        || error instanceof HttpClientErrorException.TooManyRequests;
  }
}
