package dev.gridpulse.ingestion.elexon;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gridpulse.ingestion.elexon.ElexonClient.DayAheadProduct;
import dev.gridpulse.ingestion.testing.FixtureServer;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ElexonClientTest {

  private static final LocalDate YESTERDAY = LocalDate.parse("2026-10-27");
  private static final LocalDate TODAY = LocalDate.parse("2026-10-28");

  private final FixtureServer api = ElexonFixtures.server();
  private final ElexonClient client =
      new ElexonClient(RestClient.builder(), ElexonFixtures.properties(api.baseUrl()));

  @AfterEach
  void stopServer() {
    api.close();
  }

  @Test
  void callsEachEndpointUnderTheBasePathAndReadsTheResponses() {
    assertThat(client.demandOutturn(YESTERDAY, TODAY)).hasSize(90);
    assertThat(client.fuelGeneration(YESTERDAY, TODAY)).hasSize(1800);
    assertThat(client.systemPrices(YESTERDAY)).hasSize(48);
    assertThat(client.latestDayAheadForecast(DayAheadProduct.NDF)).hasSize(64);
    assertThat(client.latestDayAheadForecast(DayAheadProduct.TSDF)).hasSize(64);

    // The fixture server only answers these exact paths; this documents what is sent.
    assertThat(api.requestedPaths())
        .containsExactly(
            "/demand/outturn?settlementDateFrom=2026-10-27&settlementDateTo=2026-10-28",
            "/datasets/FUELHH?settlementDateFrom=2026-10-27&settlementDateTo=2026-10-28",
            "/balancing/settlement/system-prices/2026-10-27",
            "/datasets/NDF",
            "/datasets/TSDF?boundary=N");
  }

  @Test
  void retriesTransientFailures() {
    api.failNext(502, 503);

    assertThat(client.latestDayAheadForecast(DayAheadProduct.NDF)).hasSize(64);
    assertThat(api.requestCount()).isEqualTo(3);
  }
}
