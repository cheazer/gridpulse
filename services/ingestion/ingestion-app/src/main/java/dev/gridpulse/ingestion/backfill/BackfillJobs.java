package dev.gridpulse.ingestion.backfill;

import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityClient;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityMapper;
import dev.gridpulse.ingestion.elexon.ElexonClient;
import dev.gridpulse.ingestion.elexon.ElexonClient.DayAheadProduct;
import dev.gridpulse.ingestion.elexon.ElexonMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
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

  private static Instant utc(LocalDate date) {
    return date.atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
