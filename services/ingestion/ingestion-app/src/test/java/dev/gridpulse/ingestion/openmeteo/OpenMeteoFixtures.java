package dev.gridpulse.ingestion.openmeteo;

import dev.gridpulse.events.Region;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoApi.LocationResponse;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoProperties.Location;
import dev.gridpulse.ingestion.testing.FixtureServer;
import dev.gridpulse.ingestion.testing.TestHttpSettings;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Real Open-Meteo responses recorded in src/test/resources/fixtures/open-meteo (see README). */
final class OpenMeteoFixtures {

  /** When the fixtures were recorded; tests use it as the poll time. */
  static final Instant POLLED_AT = Instant.parse("2026-10-28T22:24:36Z");

  /** The eight locations the fixtures were recorded for, as in application.yaml. */
  static final List<Location> LOCATIONS =
      List.of(
          new Location("london", 51.5074, -0.1278, Region.LONDON),
          new Location("birmingham", 52.4862, -1.8904, Region.WEST_MIDLANDS),
          new Location("manchester", 53.4808, -2.2426, Region.NORTH_WEST_ENGLAND),
          new Location("leeds", 53.8008, -1.5491, Region.YORKSHIRE),
          new Location("edinburgh", 55.9533, -3.1883, Region.SOUTH_SCOTLAND),
          new Location("cardiff", 51.4816, -3.1791, Region.SOUTH_WALES),
          new Location("norwich", 52.6309, 1.2974, Region.EAST_ENGLAND),
          new Location("aberdeen", 57.1497, -2.0943, Region.NORTH_SCOTLAND));

  static final String BASE_PATH = "/v1";
  private static final String DIR = "/fixtures/open-meteo/";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private OpenMeteoFixtures() {}

  /** Serves the recorded responses for exactly the requests the poller should make. */
  static FixtureServer server() {
    String all = OpenMeteoClient.coordinates(LOCATIONS) + OpenMeteoClient.COMMON;
    String london = OpenMeteoClient.coordinates(LOCATIONS.subList(0, 1)) + OpenMeteoClient.COMMON;
    return FixtureServer.start(
        BASE_PATH,
        Map.of(
            Pattern.quote("/forecast" + all + "&forecast_days=3"),
            DIR + "forecast.json",
            Pattern.quote("/archive" + all + "&start_date=2026-10-18&end_date=2026-10-27"),
            DIR + "archive.json",
            Pattern.quote("/forecast" + london + "&forecast_days=3"),
            DIR + "forecast-single.json",
            Pattern.quote(
                "/forecast"
                    + OpenMeteoClient.coordinates(LOCATIONS)
                    + OpenMeteoClient.PREVIOUS_DAY_COMMON
                    + "&start_date=2024-10-29&end_date=2024-10-30"),
            DIR + "previous-runs.json"));
  }

  static OpenMeteoProperties properties(URI baseUrl) {
    return new OpenMeteoProperties(
        baseUrl,
        baseUrl,
        baseUrl,
        false,
        3,
        Duration.ofHours(6),
        10,
        LOCATIONS,
        TestHttpSettings.fast());
  }

  static List<LocationResponse> forecast() {
    return read("forecast.json");
  }

  static List<LocationResponse> reanalysis() {
    return read("archive.json");
  }

  /** The recorded previous-runs response with the lead suffix stripped, as the client does. */
  static List<LocationResponse> dayAheadForecasts() {
    String body =
        new String(FixtureServer.resource(DIR + "previous-runs.json"), StandardCharsets.UTF_8)
            .replace(OpenMeteoClient.PREVIOUS_DAY + "\"", "\"");
    return List.of(JSON.readValue(body, LocationResponse[].class));
  }

  private static List<LocationResponse> read(String file) {
    return List.of(JSON.readValue(FixtureServer.resource(DIR + file), LocationResponse[].class));
  }
}
