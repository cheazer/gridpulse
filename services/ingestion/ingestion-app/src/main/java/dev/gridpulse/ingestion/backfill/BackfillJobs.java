package dev.gridpulse.ingestion.backfill;

import dev.gridpulse.events.GridEvent;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityClient;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityMapper;
import dev.gridpulse.ingestion.elexon.ElexonClient;
import dev.gridpulse.ingestion.elexon.ElexonClient.DayAheadProduct;
import dev.gridpulse.ingestion.elexon.ElexonMapper;
import dev.gridpulse.ingestion.neso.NesoClient;
import dev.gridpulse.ingestion.neso.NesoMapper;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoClient;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoMapper;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * The backfill jobs (ADR-0009). Each wraps a live client and mapper, so history goes through
 * exactly the same mapping as live data; chunk sizes follow each endpoint's measured per-request
 * limit.
 */
@Configuration
public class BackfillJobs {

  @Bean
  @Order(1)
  BackfillJob carbonIntensityNational(CarbonIntensityClient client, CarbonIntensityMapper mapper) {
    // Up to 31 days per request; periods with an actual become actual events, as live.
    return new ChunkedJob(
        "carbon-intensity-national",
        Period.ofDays(30),
        (from, to, now) -> mapper.national(client.national(utc(from), utc(to)), now));
  }

  @Bean
  @Order(2)
  BackfillJob elexonDemandOutturn(ElexonClient client, ElexonMapper mapper) {
    // Settlement dates, inclusive at both ends: 14 days per request.
    return new ChunkedJob(
        "elexon-demand-outturn",
        Period.ofDays(14),
        (from, to, now) -> mapper.demandOutturn(client.demandOutturn(from, to.minusDays(1)), now));
  }

  @Bean
  @Order(3)
  BackfillJob elexonGeneration(ElexonClient client, ElexonMapper mapper) {
    // FUELHH allows 7 days per request.
    return new ChunkedJob(
        "elexon-generation",
        Period.ofDays(7),
        (from, to, now) -> mapper.fuelMix(client.fuelGeneration(from, to.minusDays(1)), now));
  }

  @Bean
  @Order(4)
  BackfillJob elexonSystemPrices(ElexonClient client, ElexonMapper mapper) {
    // One settlement date per request.
    return new ChunkedJob(
        "elexon-system-prices",
        Period.ofDays(1),
        (from, to, now) -> mapper.systemPrices(client.systemPrices(from), now));
  }

  @Bean
  @Order(5)
  BackfillJob elexonNdf(ElexonClient client, ElexonMapper mapper) {
    // Every NDF revision (the Phase 2 benchmark); one day of publish times per request.
    return new ChunkedJob(
        "elexon-ndf",
        Period.ofDays(1),
        (from, to, now) ->
            mapper.dayAheadForecast(
                client.dayAheadForecastsPublished(DayAheadProduct.NDF, from, to),
                DayAheadProduct.NDF,
                now));
  }

  @Bean
  @Order(6)
  BackfillJob openMeteoReanalysis(
      OpenMeteoClient client, OpenMeteoMapper mapper, OpenMeteoProperties properties) {
    // What the weather actually was. 3 months x 8 locations stays well inside Open-Meteo's limits.
    return new ChunkedJob(
        "open-meteo-reanalysis",
        Period.ofMonths(3),
        (from, to, now) ->
            mapper.reanalysis(
                properties.locations(),
                client.reanalysis(properties.locations(), from, to.minusDays(1)),
                now));
  }

  @Bean
  @Order(7)
  BackfillJob openMeteoDayAheadForecasts(
      OpenMeteoClient client, OpenMeteoMapper mapper, OpenMeteoProperties properties) {
    // What the forecast was a day ahead: the honest training features (ADR-0008).
    return new ChunkedJob(
        "open-meteo-day-ahead-forecasts",
        Period.ofMonths(1),
        (from, to, now) ->
            mapper.dayAheadForecasts(
                properties.locations(),
                client.dayAheadForecasts(properties.locations(), from, to.minusDays(1)),
                now));
  }

  @Bean
  @Order(8)
  BackfillJob nesoHistoricDemand(NesoClient client, NesoMapper mapper) {
    // One CSV per year; a chunk downloads each year it touches and keeps its own dates.
    return new ChunkedJob(
        "neso-historic-demand",
        Period.ofYears(1),
        (from, to, now) -> {
          List<GridEvent> events = new ArrayList<>();
          for (int year = from.getYear(); year <= to.minusDays(1).getYear(); year++) {
            client
                .historicDemandCsv(year)
                .ifPresent(csv -> events.addAll(mapper.historicDemand(csv, from, to, now)));
          }
          return events;
        });
  }

  private static Instant utc(LocalDate date) {
    return date.atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
