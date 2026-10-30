package dev.gridpulse.ingestion.openmeteo;

import static dev.gridpulse.ingestion.openmeteo.OpenMeteoFixtures.LOCATIONS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.gridpulse.ingestion.testing.FixtureServer;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class OpenMeteoClientTest {

  private final FixtureServer api = OpenMeteoFixtures.server();
  private final OpenMeteoClient client =
      new OpenMeteoClient(
          RestClient.builder(),
          OpenMeteoFixtures.properties(api.baseUrl()),
          JsonMapper.builder().build());

  @AfterEach
  void stopServer() {
    api.close();
  }

  @Test
  void fetchesEveryLocationInOneRequestPerDataset() {
    assertThat(client.forecast(LOCATIONS, 3)).hasSize(8);
    assertThat(
            client.reanalysis(
                LOCATIONS, LocalDate.parse("2026-10-18"), LocalDate.parse("2026-10-27")))
        .hasSize(8);

    assertThat(api.requestedPaths())
        .hasSize(2)
        .allSatisfy(
            path ->
                assertThat(path)
                    .contains(
                        "latitude=51.5074,52.4862,53.4808,53.8008,55.9533,51.4816,52.6309,57.1497")
                    .contains("&wind_speed_unit=ms&timeformat=unixtime&timezone=GMT"));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void previousRunForecastsMapTheLeadVariablesOntoTheUsualShape(int daysEarlier) {
    var responses =
        client.previousRunForecasts(
            LOCATIONS, daysEarlier, LocalDate.parse("2024-10-29"), LocalDate.parse("2024-10-30"));

    assertThat(responses).hasSize(8);
    // The _previous_dayN suffix is stripped, so every variable is populated, not null.
    assertThat(responses.getFirst().hourly().temperature2m()).hasSize(48).doesNotContainNull();
    assertThat(responses.getFirst().hourly().windSpeed100m()).doesNotContainNull();
  }

  @Test
  void runsFromDifferentDaysForecastDifferentValues() {
    LocalDate day = LocalDate.parse("2024-10-29");
    var oneDay = client.previousRunForecasts(LOCATIONS, 1, day, day.plusDays(1));
    var twoDays = client.previousRunForecasts(LOCATIONS, 2, day, day.plusDays(1));

    assertThat(twoDays.getFirst().hourly().temperature2m())
        .isNotEqualTo(oneDay.getFirst().hourly().temperature2m());
  }

  @Test
  void handlesTheBareObjectReturnedForASingleLocation() {
    assertThat(client.forecast(LOCATIONS.subList(0, 1), 3)).hasSize(1);
  }
}
