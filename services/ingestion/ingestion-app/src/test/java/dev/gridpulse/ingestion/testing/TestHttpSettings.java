package dev.gridpulse.ingestion.testing;

import dev.gridpulse.ingestion.http.HttpSettings;
import java.time.Duration;

/** Fast settings for tests: 3 retries a few milliseconds apart, and no effective rate limit. */
public final class TestHttpSettings {

  private TestHttpSettings() {}

  public static HttpSettings fast() {
    return new HttpSettings(
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        1_000,
        100,
        new HttpSettings.Retry(3, Duration.ofMillis(10), Duration.ofMillis(50)));
  }
}
