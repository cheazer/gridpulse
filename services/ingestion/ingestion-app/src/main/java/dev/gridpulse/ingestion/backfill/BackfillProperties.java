package dev.gridpulse.ingestion.backfill;

import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backfill settings (ADR-0009).
 *
 * @param from first date to load; if null, {@code years} before {@code to}
 * @param to day after the last date to load; if null, today (UTC)
 * @param years history length when {@code from} is not set
 * @param jobs job names to run; empty runs every job
 * @param checkpointTopic compacted topic recording completed chunks
 */
@ConfigurationProperties("gridpulse.backfill")
public record BackfillProperties(
    LocalDate from, LocalDate to, int years, List<String> jobs, String checkpointTopic) {

  public BackfillProperties {
    jobs = jobs == null ? List.of() : List.copyOf(jobs);
  }
}
