package dev.gridpulse.ingestion.neso;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gridpulse.ingestion.testing.FixtureServer;
import dev.gridpulse.ingestion.testing.TestHttpSettings;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class NesoClientTest {

  private final FixtureServer portal =
      FixtureServer.start(
          "",
          Map.of(
              "/api/3/action/package_show\\?id=historic-demand-data",
              "/fixtures/neso/package-show.json",
              "/dataset/[0-9a-f-]+/resource/f6d02c0f-957b-48cb-82ee-09003f2ba759/download/demanddata_2024\\.csv",
              "/fixtures/neso/demanddata_2024-sample.csv"));

  private final NesoClient client =
      new NesoClient(
          RestClient.builder(),
          new NesoProperties(portal.baseUrl(), "historic-demand-data", TestHttpSettings.fast()));

  @AfterEach
  void stopServer() {
    portal.close();
  }

  @Test
  void findsTheYearsResourceAndDownloadsItsCsv() {
    assertThat(client.historicDemandCsv(2024))
        .hasValueSatisfying(
            csv -> assertThat(csv).startsWith("SETTLEMENT_DATE,SETTLEMENT_PERIOD,ND"));

    assertThat(portal.requestedPaths())
        .containsExactly(
            "/api/3/action/package_show?id=historic-demand-data",
            "/dataset/8f2fe0af-871c-488d-8bad-960426f24601/resource/f6d02c0f-957b-48cb-82ee-09003f2ba759/download/demanddata_2024.csv");
  }

  @Test
  void aYearNesoHasntPublishedIsEmpty() {
    assertThat(client.historicDemandCsv(2019)).isEmpty();
  }
}
