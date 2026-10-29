package dev.gridpulse.ingestion.backfill;

import java.time.LocalDate;
import java.util.Set;

/** Records which backfill chunks are complete, so an interrupted run resumes (ADR-0009). */
public interface CheckpointStore {

  /** Keys of every completed chunk, as built by {@link #key}. */
  Set<String> completed();

  /** Records a completed chunk; returns once the record is durable. */
  void markCompleted(String job, LocalDate chunkStart, LocalDate chunkEnd, int events);

  static String key(String job, LocalDate chunkStart) {
    return job + "|" + chunkStart;
  }
}
