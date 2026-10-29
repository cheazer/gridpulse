package dev.gridpulse.ingestion.carbonintensity;

import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.NationalPeriod;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.NationalResponse;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.RegionalPeriod;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.RegionalResponse;
import dev.gridpulse.ingestion.testing.FixtureServer;
import dev.gridpulse.ingestion.testing.TestHttpSettings;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Real API responses recorded in src/test/resources/fixtures/carbon-intensity (see README). */
final class Fixtures {

  /** When the fixtures were recorded; tests use it as the poll time. */
  static final Instant POLLED_AT = Instant.parse("2026-10-28T20:16:42Z");

  private static final String API_TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z";

  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private Fixtures() {}

  /** Serves the recorded responses only for correctly formatted paths, like the real API. */
  static FixtureServer server() {
    return FixtureServer.start(
        "",
        Map.of(
            "/intensity/" + API_TIME + "/fw48h", "/fixtures/carbon-intensity/national.json",
            // History (backfill): the same recorded periods serve any from/to range.
            "/intensity/" + API_TIME + "/" + API_TIME, "/fixtures/carbon-intensity/national.json",
            "/regional/intensity/" + API_TIME + "/fw48h",
                "/fixtures/carbon-intensity/regional.json"));
  }

  static List<NationalPeriod> national() {
    return JSON.readValue(
            FixtureServer.resource("/fixtures/carbon-intensity/national.json"),
            NationalResponse.class)
        .data();
  }

  static List<RegionalPeriod> regional() {
    return JSON.readValue(
            FixtureServer.resource("/fixtures/carbon-intensity/regional.json"),
            RegionalResponse.class)
        .data();
  }

  static CarbonIntensityProperties properties(URI baseUrl) {
    return new CarbonIntensityProperties(
        baseUrl,
        false,
        Duration.ofHours(2),
        Duration.ofMinutes(30),
        Duration.ofHours(3),
        TestHttpSettings.fast());
  }
}
