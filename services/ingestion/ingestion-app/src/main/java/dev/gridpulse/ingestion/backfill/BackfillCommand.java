package dev.gridpulse.ingestion.backfill;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the backfill when the app starts with the {@code backfill} profile, then lets it exit. The
 * profile also switches the live pollers off (application-backfill.yaml).
 */
@Component
@Profile("backfill")
public class BackfillCommand implements ApplicationRunner {

  private final BackfillRunner runner;

  public BackfillCommand(BackfillRunner runner) {
    this.runner = runner;
  }

  /**
   * @throws IllegalStateException if any job failed, so the process exits non-zero
   */
  @Override
  public void run(ApplicationArguments args) {
    BackfillRunner.Summary summary = runner.run();
    if (!summary.failedJobs().isEmpty()) {
      throw new IllegalStateException(
          "backfill jobs failed: " + summary.failedJobs() + "; re-run to resume");
    }
  }
}
