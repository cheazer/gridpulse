package dev.gridpulse.ingestion.openmeteo;

import dev.gridpulse.events.EventType;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.Source;
import dev.gridpulse.events.payload.Weather;
import dev.gridpulse.ingestion.RevisionBuckets;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoApi.Hourly;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoApi.LocationResponse;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoProperties.Location;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Turns Open-Meteo responses into weather events (ADR-0008). Forecasts and reanalysis are mapped
 * separately and never mixed: elapsed hours in a forecast response are not treated as observations.
 */
@Component
public class OpenMeteoMapper {

  private static final Duration HOUR = Duration.ofHours(1);

  private final Duration revisionInterval;

  public OpenMeteoMapper(OpenMeteoProperties properties) {
    this.revisionInterval = properties.revisionInterval();
  }

  /** Forecast hours starting after the poll, as revisions in the poll's 6-hour bucket. */
  public List<GridEvent> forecasts(
      List<Location> locations, List<LocationResponse> responses, Instant polledAt) {
    Instant issuedAt = RevisionBuckets.start(polledAt, revisionInterval);
    return map(locations, responses, polledAt, issuedAt, hour -> hour.isAfter(polledAt));
  }

  /** Reanalysis: what the weather actually was. Hours with no values yet are skipped. */
  public List<GridEvent> reanalysis(
      List<Location> locations, List<LocationResponse> responses, Instant polledAt) {
    return map(locations, responses, polledAt, null, hour -> true);
  }

  private static List<GridEvent> map(
      List<Location> locations,
      List<LocationResponse> responses,
      Instant polledAt,
      Instant issuedAt,
      Predicate<Instant> include) {
    List<GridEvent> events = new ArrayList<>();
    for (int i = 0; i < locations.size(); i++) {
      Location location = locations.get(i);
      Hourly hourly = responses.get(i).hourly();
      for (int h = 0; h < hourly.time().size(); h++) {
        Instant hour = Instant.ofEpochSecond(hourly.time().get(h));
        if (hour.getEpochSecond() % HOUR.toSeconds() != 0 || !include.test(hour)) {
          continue;
        }
        var weather =
            new Weather(
                new Weather.Location(location.id(), location.latitude(), location.longitude()),
                hourly.temperature2m().get(h),
                hourly.windSpeed10m().get(h),
                hourly.windSpeed100m().get(h),
                hourly.shortwaveRadiation().get(h),
                hourly.cloudCover().get(h));
        if (isEmpty(weather)) {
          continue;
        }
        events.add(
            GridEvent.create(
                EventType.WEATHER,
                Source.OPEN_METEO,
                location.id(),
                location.region(),
                hour,
                hour.plus(HOUR),
                null, // hourly weather isn't aligned to settlement periods
                issuedAt,
                polledAt.truncatedTo(ChronoUnit.MILLIS),
                weather));
      }
    }
    return events;
  }

  private static boolean isEmpty(Weather w) {
    return w.temperature2mC() == null
        && w.windSpeed10mMs() == null
        && w.windSpeed100mMs() == null
        && w.shortwaveRadiationWm2() == null
        && w.cloudCoverPct() == null;
  }
}
