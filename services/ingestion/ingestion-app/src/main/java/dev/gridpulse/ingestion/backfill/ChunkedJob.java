package dev.gridpulse.ingestion.backfill;

import dev.gridpulse.events.GridEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/** A {@link BackfillJob} defined by a name, a chunk size and a fetch function. */
public record ChunkedJob(String name, Period chunk, Fetch fetch) implements BackfillJob {

  /** Fetches and maps one chunk: {@code from} inclusive to {@code to} exclusive. */
  @FunctionalInterface
  public interface Fetch {
    List<GridEvent> apply(LocalDate from, LocalDate to, Instant now);
  }

  @Override
  public List<GridEvent> fetch(LocalDate from, LocalDate to, Instant now) {
    return fetch.apply(from, to, now);
  }
}
