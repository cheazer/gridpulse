package dev.gridpulse.ingestion.carbonintensity;

import static dev.gridpulse.ingestion.carbonintensity.Fixtures.POLLED_AT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventRules;
import dev.gridpulse.events.EventValidator;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.Region;
import dev.gridpulse.events.payload.CarbonIntensity;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Runs the recorded API responses through the mapper. Poll time: 2026-10-28T20:16:42Z. */
class CarbonIntensityMapperTest {

  private final CarbonIntensityMapper mapper =
      new CarbonIntensityMapper(Fixtures.properties(URI.create("http://unused")));

  @Test
  void nationalMeasuredPeriodsBecomeActualsAndFuturePeriodsBecomeForecasts() {
    List<GridEvent> events = mapper.national(Fixtures.national(), POLLED_AT);

    List<GridEvent> actuals = events.stream().filter(e -> !e.isForecast()).toList();
    List<GridEvent> forecasts = events.stream().filter(GridEvent::isForecast).toList();

    // 18:00-19:30 had been measured by 20:16.
    assertThat(actuals)
        .extracting(GridEvent::validFrom)
        .containsExactly(
            Instant.parse("2026-10-28T18:00:00Z"),
            Instant.parse("2026-10-28T18:30:00Z"),
            Instant.parse("2026-10-28T19:00:00Z"),
            Instant.parse("2026-10-28T19:30:00Z"));
    assertThat(actuals).allSatisfy(e -> assertThat(intensity(e).actualGco2PerKwh()).isNotNull());

    // 20:00 is in progress with no actual yet: skipped until a later poll measures it.
    assertThat(events).noneMatch(e -> e.validFrom().equals(Instant.parse("2026-10-28T20:00:00Z")));

    // 20:30 onwards: 91 forecast periods, all in the 20:00 revision bucket.
    assertThat(forecasts).hasSize(91);
    assertThat(forecasts).allSatisfy(e -> assertThat(e.validFrom()).isAfter(POLLED_AT));
    assertThat(forecasts)
        .extracting(GridEvent::issuedAt)
        .containsOnly(Instant.parse("2026-10-28T20:00:00Z"));
    assertThat(events).extracting(GridEvent::region).containsOnly(Region.GB);
  }

  @Test
  void regionalForecastsCoverSeventeenRegionsAndSkipTheApiGbRow() {
    List<GridEvent> events = mapper.regional(Fixtures.regional(), POLLED_AT);

    assertThat(events).extracting(GridEvent::region).doesNotContain(Region.GB);
    assertThat(events.stream().map(GridEvent::region).distinct()).hasSize(17);
    // 96 periods from 20:00; 20:00 has already started, leaving 95 per region.
    assertThat(events).hasSize(95 * 17);
    assertThat(events)
        .extracting(GridEvent::issuedAt)
        .containsOnly(Instant.parse("2026-10-28T18:00:00Z")); // 3-hour bucket
  }

  @Test
  void everyMappedEventIsSchemaValidAndFollowsTheRules() {
    var codec = new EventCodec();
    var validator = new EventValidator();
    List<GridEvent> events =
        Stream.concat(
                mapper.national(Fixtures.national(), POLLED_AT).stream(),
                mapper.regional(Fixtures.regional(), POLLED_AT).stream())
            .toList();

    assertThat(events).hasSize(95 + 1615);
    assertThat(events)
        .allSatisfy(
            e -> {
              assertThat(validator.validate(codec.toTree(e))).isEmpty();
              assertThat(EventRules.violations(e)).isEmpty();
            });
  }

  @Test
  void repollingInsideARevisionBucketGivesTheSameIds() {
    Set<UUID> first = ids(mapper.national(Fixtures.national(), POLLED_AT));
    Set<UUID> fiveMinutesLater =
        ids(mapper.national(Fixtures.national(), POLLED_AT.plus(Duration.ofMinutes(5))));

    assertThat(fiveMinutesLater).isEqualTo(first);
  }

  @Test
  void theNextRevisionBucketGivesNewForecastIdsButTheSameActualIds() {
    List<GridEvent> first = mapper.national(Fixtures.national(), POLLED_AT);
    List<GridEvent> nextBucket =
        mapper.national(Fixtures.national(), POLLED_AT.plus(Duration.ofMinutes(15)));

    assertThat(ids(actualsOf(nextBucket))).isEqualTo(ids(actualsOf(first)));
    assertThat(ids(forecastsOf(nextBucket))).doesNotContainAnyElementsOf(ids(forecastsOf(first)));
  }

  @Test
  void revisionBucketsAlignToTheInterval() {
    assertThat(CarbonIntensityMapper.revisionBucket(POLLED_AT, Duration.ofMinutes(30)))
        .isEqualTo(Instant.parse("2026-10-28T20:00:00Z"));
    assertThat(CarbonIntensityMapper.revisionBucket(POLLED_AT, Duration.ofHours(3)))
        .isEqualTo(Instant.parse("2026-10-28T18:00:00Z"));
  }

  private static CarbonIntensity intensity(GridEvent event) {
    return (CarbonIntensity) event.payload();
  }

  private static List<GridEvent> actualsOf(List<GridEvent> events) {
    return events.stream().filter(e -> !e.isForecast()).toList();
  }

  private static List<GridEvent> forecastsOf(List<GridEvent> events) {
    return events.stream().filter(GridEvent::isForecast).toList();
  }

  private static Set<UUID> ids(List<GridEvent> events) {
    return events.stream().map(GridEvent::eventId).collect(Collectors.toSet());
  }
}
