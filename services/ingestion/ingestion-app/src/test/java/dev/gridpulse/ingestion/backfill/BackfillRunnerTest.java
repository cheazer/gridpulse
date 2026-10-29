package dev.gridpulse.ingestion.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.gridpulse.events.GridEvent;
import dev.gridpulse.ingestion.publish.EventPublisher;
import dev.gridpulse.ingestion.publish.EventPublisher.PublishResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackfillRunnerTest {

  private static final LocalDate FROM = LocalDate.parse("2024-01-01");
  private static final LocalDate TO = LocalDate.parse("2024-01-31");

  private final InMemoryCheckpoints checkpoints = new InMemoryCheckpoints();
  private final EventPublisher publisher = mock(EventPublisher.class);
  private final List<String> fetched = new ArrayList<>();

  @BeforeEach
  void publisherAcceptsEverything() {
    when(publisher.publishAll(anyList()))
        .thenAnswer(call -> new PublishResult(call.<List<?>>getArgument(0).size(), 0));
  }

  @Test
  void walksChunksOldestFirstAndClipsTheLastOne() {
    runner(List.of(job("a", 14))).run();

    assertThat(fetched)
        .containsExactly(
            "a 2024-01-01..2024-01-15", "a 2024-01-15..2024-01-29", "a 2024-01-29..2024-01-31");
    assertThat(checkpoints.done)
        .containsExactlyInAnyOrder("a|2024-01-01", "a|2024-01-15", "a|2024-01-29");
  }

  @Test
  void skipsCompletedChunksSoARerunResumes() {
    checkpoints.done.addAll(Set.of("a|2024-01-01", "a|2024-01-15"));

    BackfillRunner.Summary summary = runner(List.of(job("a", 14))).run();

    assertThat(fetched).containsExactly("a 2024-01-29..2024-01-31");
    assertThat(summary.chunksSkipped()).isEqualTo(2);
    assertThat(summary.chunksRun()).isEqualTo(1);
  }

  @Test
  void aFailingChunkStopsItsJobWithoutCheckpointButOtherJobsRun() {
    BackfillJob failing =
        new ChunkedJob(
            "flaky",
            Period.ofDays(14),
            (from, to, now) -> {
              if (from.equals(LocalDate.parse("2024-01-15"))) {
                throw new IllegalStateException("source down");
              }
              fetched.add("flaky " + from + ".." + to);
              return List.of();
            });

    BackfillRunner.Summary summary = runner(List.of(failing, job("b", 30))).run();

    assertThat(summary.failedJobs()).containsExactly("flaky");
    // flaky stopped at its failing chunk: no checkpoint for it and nothing after it.
    assertThat(checkpoints.done).containsExactlyInAnyOrder("flaky|2024-01-01", "b|2024-01-01");
    assertThat(fetched).containsExactly("flaky 2024-01-01..2024-01-15", "b 2024-01-01..2024-01-31");
  }

  @Test
  void runsOnlySelectedJobsAndRejectsUnknownNames() {
    runner(List.of(job("a", 30), job("b", 30)), List.of("b")).run();
    assertThat(fetched).containsExactly("b 2024-01-01..2024-01-31");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> runner(List.of(job("a", 30)), List.of("typo")).run())
        .withMessageContaining("unknown backfill jobs [typo]");
  }

  private BackfillJob job(String name, int chunkDays) {
    return new ChunkedJob(
        name,
        Period.ofDays(chunkDays),
        (from, to, now) -> {
          fetched.add(name + " " + from + ".." + to);
          return List.<GridEvent>of();
        });
  }

  private BackfillRunner runner(List<BackfillJob> jobs) {
    return runner(jobs, List.of());
  }

  private BackfillRunner runner(List<BackfillJob> jobs, List<String> selected) {
    return new BackfillRunner(
        jobs,
        checkpoints,
        publisher,
        new BackfillProperties(FROM, TO, 2, selected, "unused"),
        Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC));
  }

  private static final class InMemoryCheckpoints implements CheckpointStore {
    final Set<String> done = new HashSet<>();

    @Override
    public Set<String> completed() {
      return Set.copyOf(done);
    }

    @Override
    public void markCompleted(String job, LocalDate chunkStart, LocalDate chunkEnd, int events) {
      done.add(CheckpointStore.key(job, chunkStart));
    }
  }
}
