package dev.gridpulse.ingestion.backfill;

import dev.gridpulse.events.GridEvent;
import dev.gridpulse.ingestion.publish.EventPublisher;
import dev.gridpulse.ingestion.publish.EventPublisher.PublishResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Walks every selected job's chunks oldest first: fetch, publish, wait for Kafka, checkpoint
 * (ADR-0009). Completed chunks are skipped, so re-running resumes an interrupted backfill.
 */
@Component
public class BackfillRunner {

  private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);

  private final List<BackfillJob> jobs;
  private final CheckpointStore checkpoints;
  private final EventPublisher publisher;
  private final BackfillProperties properties;
  private final Clock clock;

  public BackfillRunner(
      List<BackfillJob> jobs,
      CheckpointStore checkpoints,
      EventPublisher publisher,
      BackfillProperties properties,
      Clock clock) {
    this.jobs = jobs;
    this.checkpoints = checkpoints;
    this.publisher = publisher;
    this.properties = properties;
    this.clock = clock;
  }

  /** What one run did. A job in {@code failedJobs} stopped at its first failing chunk. */
  public record Summary(int chunksRun, int chunksSkipped, long events, List<String> failedJobs) {}

  public Summary run() {
    Instant now = clock.instant();
    LocalDate to =
        properties.to() != null ? properties.to() : LocalDate.ofInstant(now, ZoneOffset.UTC);
    LocalDate from =
        properties.from() != null ? properties.from() : to.minusYears(properties.years());
    Set<String> done = checkpoints.completed();
    log.info("Backfill {} to {} (exclusive); {} chunks already complete", from, to, done.size());

    int run = 0;
    int skipped = 0;
    long events = 0;
    List<String> failed = new ArrayList<>();
    for (BackfillJob job : selectedJobs()) {
      try {
        for (LocalDate start = from; start.isBefore(to); start = start.plus(job.chunk())) {
          LocalDate end = min(start.plus(job.chunk()), to);
          if (done.contains(CheckpointStore.key(job.name(), start))) {
            skipped++;
            continue;
          }
          List<GridEvent> chunk = job.fetch(start, end, now);
          PublishResult result = publisher.publishAll(chunk);
          checkpoints.markCompleted(job.name(), start, end, chunk.size());
          run++;
          events += chunk.size();
          log.info(
              "Backfill {} {}..{}: {} events ({} dead-lettered)",
              job.name(),
              start,
              end,
              result.published(),
              result.deadLettered());
        }
      } catch (RuntimeException e) {
        // Later chunks would leave a gap behind them, so stop this job; others carry on.
        failed.add(job.name());
        log.error(
            "Backfill job {} stopped; re-run to resume from its last checkpoint", job.name(), e);
      }
    }
    var summary = new Summary(run, skipped, events, List.copyOf(failed));
    log.info("Backfill finished: {}", summary);
    return summary;
  }

  private List<BackfillJob> selectedJobs() {
    if (properties.jobs().isEmpty()) {
      return jobs;
    }
    List<String> unknown =
        properties.jobs().stream()
            .filter(name -> jobs.stream().noneMatch(job -> job.name().equals(name)))
            .toList();
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          "unknown backfill jobs "
              + unknown
              + "; available: "
              + jobs.stream().map(BackfillJob::name).toList());
    }
    return jobs.stream().filter(job -> properties.jobs().contains(job.name())).toList();
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }
}
