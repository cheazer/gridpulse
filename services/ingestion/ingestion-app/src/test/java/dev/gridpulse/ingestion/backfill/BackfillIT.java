package dev.gridpulse.ingestion.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gridpulse.ingestion.elexon.ElexonTestServer;
import dev.gridpulse.ingestion.testing.FixtureServer;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * A real backfill job (one day of NDF revisions from recorded responses) against Redpanda, with
 * checkpoints in a real Kafka topic: a second run must find the chunk complete and skip it.
 */
@SpringBootTest(
    properties = {
      "gridpulse.backfill.from=2026-10-28",
      "gridpulse.backfill.to=2026-10-29",
      "gridpulse.backfill.jobs=elexon-ndf",
      "gridpulse.backfill.checkpoint-topic=backfill-it-checkpoints"
    })
@ActiveProfiles("it") // the backfill profile would run on start-up; this test calls run() itself
@Testcontainers
class BackfillIT {

  @Container @ServiceConnection
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.2.4");

  static final FixtureServer ELEXON = ElexonTestServer.start();

  @DynamicPropertySource
  static void sourceUrl(DynamicPropertyRegistry registry) {
    registry.add("gridpulse.sources.elexon.base-url", () -> ELEXON.baseUrl().toString());
  }

  @AfterAll
  static void stopApi() {
    ELEXON.close();
  }

  @Autowired BackfillRunner runner;
  @Autowired CheckpointStore checkpoints;

  @Test
  void runsAChunkCheckpointsItAndSkipsItOnTheNextRun() {
    BackfillRunner.Summary first = runner.run();

    assertThat(first).isEqualTo(new BackfillRunner.Summary(1, 0, 64, List.of()));
    assertThat(checkpoints.completed()).containsExactly("elexon-ndf|2026-10-28");

    BackfillRunner.Summary second = runner.run();

    assertThat(second).isEqualTo(new BackfillRunner.Summary(0, 1, 0, List.of()));
    assertThat(ELEXON.requestCount()).isEqualTo(1); // nothing re-fetched
  }
}
