package dev.gridpulse.ingestion.openmeteo;

import static dev.gridpulse.ingestion.openmeteo.OpenMeteoFixtures.LOCATIONS;
import static dev.gridpulse.ingestion.openmeteo.OpenMeteoFixtures.POLLED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventRules;
import dev.gridpulse.events.EventValidator;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.Region;
import dev.gridpulse.events.payload.Weather;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoApi.Hourly;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoApi.LocationResponse;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Runs the recorded Open-Meteo responses through the mapper. Poll time: 2026-10-28T22:24:36Z. */
class OpenMeteoMapperTest {

  private final OpenMeteoMapper mapper =
      new OpenMeteoMapper(OpenMeteoFixtures.properties(URI.create("http://unused")));

  @Test
  void forecastsCoverOnlyHoursStartingAfterThePollInTheSixHourBucket() {
    List<GridEvent> events = mapper.forecasts(LOCATIONS, OpenMeteoFixtures.forecast(), POLLED_AT);

    // 72 hours from 28 Oct 00:00; 23:00 onwards is after 22:24, leaving 49 per location.
    assertThat(events).hasSize(49 * 8);
    assertThat(events).allSatisfy(e -> assertThat(e.validFrom()).isAfter(POLLED_AT));
    assertThat(events)
        .extracting(GridEvent::issuedAt)
        .containsOnly(Instant.parse("2026-10-28T18:00:00Z"));
    assertThat(events.getFirst().validFrom()).isEqualTo(Instant.parse("2026-10-28T23:00:00Z"));
  }

  @Test
  void reanalysisIsEveryHourAsActuals() {
    List<GridEvent> events =
        mapper.reanalysis(LOCATIONS, OpenMeteoFixtures.reanalysis(), POLLED_AT);

    assertThat(events).hasSize(240 * 8);
    assertThat(events).allSatisfy(e -> assertThat(e.isForecast()).isFalse());
    assertThat(events.getFirst().validFrom()).isEqualTo(Instant.parse("2026-10-18T00:00:00Z"));
  }

  @Test
  void eventsUseTheConfiguredLocationNotTheGridSnappedOne() {
    List<GridEvent> events = mapper.forecasts(LOCATIONS, OpenMeteoFixtures.forecast(), POLLED_AT);

    assertThat(events)
        .filteredOn(e -> e.series().equals("aberdeen"))
        .first()
        .extracting(GridEvent::region, e -> ((Weather) e.payload()).location())
        .containsExactly(Region.NORTH_SCOTLAND, new Weather.Location("aberdeen", 57.1497, -2.0943));
    assertThat(events.stream().map(GridEvent::series).distinct())
        .containsExactlyElementsOf(LOCATIONS.stream().map(l -> l.id()).toList());
  }

  @Test
  void everyMappedEventIsSchemaValidAndFollowsTheRules() {
    var codec = new EventCodec();
    var validator = new EventValidator();
    List<GridEvent> events =
        Stream.concat(
                mapper.forecasts(LOCATIONS, OpenMeteoFixtures.forecast(), POLLED_AT).stream(),
                mapper.reanalysis(LOCATIONS, OpenMeteoFixtures.reanalysis(), POLLED_AT).stream())
            .toList();

    assertThat(events).hasSize(392 + 1920);
    assertThat(events)
        .allSatisfy(
            e -> {
              assertThat(validator.validate(codec.toTree(e))).isEmpty();
              assertThat(EventRules.violations(e)).isEmpty();
            });
  }

  @Test
  void dayAheadForecastsAreRevisionsIssuedInTheBucketADayEarlier() {
    List<GridEvent> events =
        mapper.dayAheadForecasts(LOCATIONS, OpenMeteoFixtures.dayAheadForecasts(), POLLED_AT);

    assertThat(events).hasSize(48 * 8);
    // 05:00 on 29 Oct 2024 was forecast a day earlier: the 6-hour bucket containing 05:00 on the
    // 28th.
    GridEvent fiveAm =
        events.stream()
            .filter(e -> e.validFrom().equals(Instant.parse("2024-10-29T05:00:00Z")))
            .findFirst()
            .orElseThrow();
    assertThat(fiveAm.issuedAt()).isEqualTo(Instant.parse("2024-10-28T00:00:00Z"));
    assertThat(events).allSatisfy(e -> assertThat(e.issuedAt()).isBefore(e.validFrom()));

    var codec = new EventCodec();
    var validator = new EventValidator();
    assertThat(events)
        .allSatisfy(
            e -> {
              assertThat(validator.validate(codec.toTree(e))).isEmpty();
              assertThat(EventRules.violations(e)).isEmpty();
            });
  }

  @Test
  void hoursWithNoValuesAreSkippedButPartialHoursAreKept() {
    long hour = Instant.parse("2026-10-20T00:00:00Z").getEpochSecond();
    var hourly =
        new Hourly(
            List.of(hour, hour + 3600),
            Arrays.asList(null, 9.5),
            Arrays.asList(null, null),
            Arrays.asList(null, null),
            Arrays.asList(null, null),
            Arrays.asList(null, null));

    List<GridEvent> events =
        mapper.reanalysis(
            LOCATIONS.subList(0, 1), List.of(new LocationResponse(hourly)), POLLED_AT);

    assertThat(events)
        .extracting(GridEvent::validFrom, e -> ((Weather) e.payload()).temperature2mC())
        .containsExactly(tuple(Instant.parse("2026-10-20T01:00:00Z"), 9.5));
  }
}
