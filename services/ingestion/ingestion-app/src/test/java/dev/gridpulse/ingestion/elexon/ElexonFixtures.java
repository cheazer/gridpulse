package dev.gridpulse.ingestion.elexon;

import dev.gridpulse.ingestion.elexon.ElexonApi.DemandForecast;
import dev.gridpulse.ingestion.elexon.ElexonApi.DemandForecastResponse;
import dev.gridpulse.ingestion.elexon.ElexonApi.DemandOutturn;
import dev.gridpulse.ingestion.elexon.ElexonApi.DemandOutturnResponse;
import dev.gridpulse.ingestion.elexon.ElexonApi.FuelGeneration;
import dev.gridpulse.ingestion.elexon.ElexonApi.FuelGenerationResponse;
import dev.gridpulse.ingestion.elexon.ElexonApi.SystemPrice;
import dev.gridpulse.ingestion.elexon.ElexonApi.SystemPriceResponse;
import dev.gridpulse.ingestion.testing.FixtureServer;
import dev.gridpulse.ingestion.testing.TestHttpSettings;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Real Elexon responses recorded in src/test/resources/fixtures/elexon (see README). */
final class ElexonFixtures {

  /** When the fixtures were recorded; tests use it as the poll time. */
  static final Instant POLLED_AT = Instant.parse("2026-10-28T21:10:01Z");

  static final String BASE_PATH = "/bmrs/api/v1";
  private static final String DIR = "/fixtures/elexon/";

  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private ElexonFixtures() {}

  /** Serves the recorded responses for exactly the requests the poller should make. */
  static FixtureServer server() {
    String days = "settlementDateFrom=2026-10-27&settlementDateTo=2026-10-28";
    return FixtureServer.start(
        BASE_PATH,
        Map.of(
            "/demand/outturn\\?" + days,
            DIR + "demand-outturn.json",
            "/datasets/FUELHH\\?" + days,
            DIR + "fuelhh.json",
            "/balancing/settlement/system-prices/2026-10-27",
            DIR + "system-prices-2026-10-27.json",
            "/balancing/settlement/system-prices/2026-10-28",
            DIR + "system-prices-2026-10-28.json",
            "/datasets/NDF",
            DIR + "ndf.json",
            "/datasets/TSDF\\?boundary=N",
            DIR + "tsdf.json"));
  }

  static ElexonProperties properties(URI baseUrl) {
    return new ElexonProperties(baseUrl, false, 1, TestHttpSettings.fast());
  }

  static List<DemandOutturn> demandOutturn() {
    return read("demand-outturn.json", DemandOutturnResponse.class).data();
  }

  static List<FuelGeneration> fuelGeneration() {
    return read("fuelhh.json", FuelGenerationResponse.class).data();
  }

  static List<SystemPrice> systemPrices(String date) {
    return read("system-prices-" + date + ".json", SystemPriceResponse.class).data();
  }

  static List<DemandForecast> forecast(String product) {
    return read(product + ".json", DemandForecastResponse.class).data();
  }

  private static <T> T read(String file, Class<T> type) {
    return JSON.readValue(FixtureServer.resource(DIR + file), type);
  }
}
