package dev.gridpulse.ingestion.carbonintensity;

import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.NationalPeriod;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.NationalResponse;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.RegionalPeriod;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.RegionalResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Real API responses recorded in src/test/resources/fixtures/carbon-intensity (see README). */
final class Fixtures {

  /** When the fixtures were recorded; tests use it as the poll time. */
  static final Instant POLLED_AT = Instant.parse("2026-10-28T20:16:42Z");

  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private Fixtures() {}

  static byte[] bytes(String name) {
    try (InputStream in =
        Fixtures.class.getResourceAsStream("/fixtures/carbon-intensity/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("no fixture " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<NationalPeriod> national() {
    return JSON.readValue(bytes("national.json"), NationalResponse.class).data();
  }

  static List<RegionalPeriod> regional() {
    return JSON.readValue(bytes("regional.json"), RegionalResponse.class).data();
  }

  static CarbonIntensityProperties properties(URI baseUrl) {
    return new CarbonIntensityProperties(
        baseUrl,
        false,
        Duration.ofHours(2),
        Duration.ofMinutes(30),
        Duration.ofHours(3),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        new CarbonIntensityProperties.Retry(3, Duration.ofMillis(10), Duration.ofMillis(50)));
  }
}
