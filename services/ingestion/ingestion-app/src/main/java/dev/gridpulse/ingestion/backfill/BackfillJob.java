package dev.gridpulse.ingestion.backfill;

import dev.gridpulse.events.GridEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/**
 * One historical dataset, fetched a chunk at a time (ADR-0009). Implementations reuse the live
 * clients and mappers, so history and live data are mapped identically.
 */
public interface BackfillJob {

  /** Stable name: part of every checkpoint key, so renaming a job restarts it. */
  String name();

  /** Chunk size, within the source's per-request limit. */
  Period chunk();

  /**
   * Events for dates {@code from} (inclusive) to {@code to} (exclusive).
   *
   * @param now the run's clock, used as {@code ingested_at} and to tell history from the future
   */
  List<GridEvent> fetch(LocalDate from, LocalDate to, Instant now);
}
