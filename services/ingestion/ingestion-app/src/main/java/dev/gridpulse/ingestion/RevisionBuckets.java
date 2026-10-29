package dev.gridpulse.ingestion;

import java.time.Duration;
import java.time.Instant;

/**
 * Forecast revision buckets (ADR-0006): sources that don't publish an issue time get the poll time
 * truncated to an interval as their {@code issued_at}, so every poll inside one bucket produces the
 * same event IDs and updates the stored revision instead of adding rows.
 */
public final class RevisionBuckets {

  private RevisionBuckets() {}

  /** The start of the bucket containing {@code polledAt}, aligned to the Unix epoch (UTC). */
  public static Instant start(Instant polledAt, Duration interval) {
    long seconds = polledAt.getEpochSecond();
    return Instant.ofEpochSecond(seconds - Math.floorMod(seconds, interval.toSeconds()));
  }
}
