package dev.gridpulse.ingestion.carbonintensity;

import static java.util.Map.entry;

import dev.gridpulse.events.EventType;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.Region;
import dev.gridpulse.events.SettlementPeriods;
import dev.gridpulse.events.Source;
import dev.gridpulse.events.payload.CarbonIntensity;
import dev.gridpulse.events.payload.CarbonIntensity.IntensityIndex;
import dev.gridpulse.ingestion.RevisionBuckets;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.NationalPeriod;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.RegionData;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.RegionalPeriod;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns Carbon Intensity API responses into events (ADR-0006). Pure apart from logging: the poll
 * time is passed in, so the same input always gives the same events.
 */
@Component
public class CarbonIntensityMapper {

  private static final Logger log = LoggerFactory.getLogger(CarbonIntensityMapper.class);
  private static final DateTimeFormatter API_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mmX");
  private static final String SERIES = "intensity";

  /** The API's numeric region ids. Id 18 is its own GB row, deliberately not mapped (ADR-0006). */
  private static final Map<Integer, Region> REGIONS =
      Map.ofEntries(
          entry(1, Region.NORTH_SCOTLAND),
          entry(2, Region.SOUTH_SCOTLAND),
          entry(3, Region.NORTH_WEST_ENGLAND),
          entry(4, Region.NORTH_EAST_ENGLAND),
          entry(5, Region.YORKSHIRE),
          entry(6, Region.NORTH_WALES_MERSEYSIDE),
          entry(7, Region.SOUTH_WALES),
          entry(8, Region.WEST_MIDLANDS),
          entry(9, Region.EAST_MIDLANDS),
          entry(10, Region.EAST_ENGLAND),
          entry(11, Region.SOUTH_WEST_ENGLAND),
          entry(12, Region.SOUTH_ENGLAND),
          entry(13, Region.LONDON),
          entry(14, Region.SOUTH_EAST_ENGLAND),
          entry(15, Region.ENGLAND),
          entry(16, Region.SCOTLAND),
          entry(17, Region.WALES));

  private static final int REGIONAL_GB_ID = 18;

  private final Duration nationalRevisionInterval;
  private final Duration regionalRevisionInterval;

  public CarbonIntensityMapper(CarbonIntensityProperties properties) {
    this.nationalRevisionInterval = properties.nationalRevisionInterval();
    this.regionalRevisionInterval = properties.regionalRevisionInterval();
  }

  /**
   * Measured periods become actual events. Periods that haven't started yet become forecast
   * revisions. Periods in progress or awaiting their actual are skipped until a later poll.
   */
  public List<GridEvent> national(List<NationalPeriod> periods, Instant polledAt) {
    Instant revision = RevisionBuckets.start(polledAt, nationalRevisionInterval);
    List<GridEvent> events = new ArrayList<>();
    for (NationalPeriod period : periods) {
      Instant from = parse(period.from());
      var intensity = period.intensity();
      if (intensity.actual() != null) {
        events.add(
            event(
                Region.GB,
                from,
                parse(period.to()),
                null,
                polledAt,
                new CarbonIntensity(
                    intensity.forecast(), intensity.actual(), index(intensity.index()))));
      } else if (!from.isBefore(polledAt) && intensity.forecast() != null) {
        events.add(
            event(
                Region.GB,
                from,
                parse(period.to()),
                revision,
                polledAt,
                new CarbonIntensity(intensity.forecast(), null, index(intensity.index()))));
      }
    }
    return events;
  }

  /** Regional data is forecast-only: one revision per region for each period not yet started. */
  public List<GridEvent> regional(List<RegionalPeriod> periods, Instant polledAt) {
    Instant revision = RevisionBuckets.start(polledAt, regionalRevisionInterval);
    List<GridEvent> events = new ArrayList<>();
    for (RegionalPeriod period : periods) {
      Instant from = parse(period.from());
      if (from.isBefore(polledAt)) {
        continue;
      }
      Instant to = parse(period.to());
      for (RegionData data : period.regions()) {
        Region region = REGIONS.get(data.regionid());
        if (region == null) {
          if (data.regionid() != REGIONAL_GB_ID) {
            log.warn(
                "Skipping unknown Carbon Intensity region {} ({})",
                data.regionid(),
                data.shortname());
          }
          continue;
        }
        var intensity = data.intensity();
        if (intensity.forecast() != null) {
          events.add(
              event(
                  region,
                  from,
                  to,
                  revision,
                  polledAt,
                  new CarbonIntensity(intensity.forecast(), null, index(intensity.index()))));
        }
      }
    }
    return events;
  }

  private static GridEvent event(
      Region region,
      Instant from,
      Instant to,
      Instant issuedAt,
      Instant polledAt,
      CarbonIntensity payload) {
    if (!SettlementPeriods.isAligned(from)) {
      throw new IllegalStateException(
          "Carbon Intensity period not on a half-hour boundary: " + from);
    }
    return GridEvent.create(
        EventType.CARBON_INTENSITY,
        Source.NESO_CARBON_INTENSITY,
        SERIES,
        region,
        from,
        to,
        SettlementPeriods.of(from),
        issuedAt,
        polledAt.truncatedTo(ChronoUnit.MILLIS),
        payload);
  }

  private static Instant parse(String apiTime) {
    return OffsetDateTime.parse(apiTime, API_TIME).toInstant();
  }

  private static IntensityIndex index(String wireName) {
    return wireName == null ? null : IntensityIndex.fromWireName(wireName);
  }
}
