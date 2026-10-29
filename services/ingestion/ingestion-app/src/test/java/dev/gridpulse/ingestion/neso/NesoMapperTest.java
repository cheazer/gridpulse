package dev.gridpulse.ingestion.neso;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventRules;
import dev.gridpulse.events.EventValidator;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.payload.Demand;
import dev.gridpulse.events.payload.Demand.DemandType;
import dev.gridpulse.events.payload.GenerationMix;
import dev.gridpulse.events.payload.GenerationMix.Fuel;
import dev.gridpulse.ingestion.testing.FixtureServer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Real slices of the NESO yearly files, including both clock changes and both file formats. */
class NesoMapperTest {

  private static final Instant POLLED_AT = Instant.parse("2026-10-29T12:00:00Z");

  private final NesoMapper mapper = new NesoMapper();

  @Test
  void oldFormatFileWithFiftyPeriodsOnTheAutumnClockChange() {
    // 2024: DD-MON-YYYY dates and no forecast/actual column. 26-28 Oct: 48 + 50 + 48 periods.
    List<GridEvent> events = map("demanddata_2024-sample.csv", "2024-10-26", "2024-10-29");

    assertThat(events).hasSize(146 * 3); // ND, TSD and embedded generation per period
    GridEvent lastPeriodOfLongDay =
        events.stream()
            .filter(e -> e.series().equals("nd"))
            .filter(e -> e.settlementDate().equals(LocalDate.parse("2024-10-27")))
            .filter(e -> e.settlementPeriod() == 50)
            .findFirst()
            .orElseThrow();
    assertThat(lastPeriodOfLongDay.validFrom()).isEqualTo(Instant.parse("2024-10-27T23:30:00Z"));
    assertThat(((Demand) lastPeriodOfLongDay.payload()).demandType()).isEqualTo(DemandType.ND);
  }

  @Test
  void newFormatFileWithFortySixPeriodsOnTheSpringClockChange() {
    // 2026: ISO dates and a FORECAST_ACTUAL_INDICATOR column. 28-30 Mar: 48 + 46 + 48 periods.
    List<GridEvent> events = map("demanddataupdate_2026-sample.csv", "2026-03-28", "2026-03-31");

    assertThat(events).hasSize(142 * 3);
    assertThat(
            events.stream()
                .filter(e -> e.series().equals("tsd"))
                .filter(e -> e.settlementDate().equals(LocalDate.parse("2026-03-29"))))
        .hasSize(46);
  }

  @Test
  void embeddedSolarAndWindBecomeAGenerationMixEvent() {
    List<GridEvent> events = map("demanddataupdate_2026-sample.csv", "2026-03-28", "2026-03-29");

    assertThat(events)
        .filteredOn(e -> e.series().equals("embedded"))
        .hasSize(48)
        .allSatisfy(
            e ->
                assertThat(((GenerationMix) e.payload()).fuels())
                    .extracting(GenerationMix.FuelReading::fuel)
                    .containsExactly(Fuel.SOLAR, Fuel.WIND));
  }

  @Test
  void onlyRowsInsideTheWindowAreMapped() {
    assertThat(map("demanddata_2024-sample.csv", "2024-10-27", "2024-10-28")).hasSize(50 * 3);
  }

  @Test
  void rowsMarkedAsForecastAreSkipped() {
    String csv =
        """
        SETTLEMENT_DATE,SETTLEMENT_PERIOD,ND,FORECAST_ACTUAL_INDICATOR,TSD,EMBEDDED_WIND_GENERATION,EMBEDDED_SOLAR_GENERATION
        2026-10-20,1,25000,A,28000,1500,0
        2026-10-20,2,24800,F,27800,1490,0
        """;

    List<GridEvent> events =
        mapper.historicDemand(
            csv, LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-21"), POLLED_AT);

    assertThat(events).extracting(GridEvent::settlementPeriod).containsOnly(1);
  }

  @Test
  void everyEventIsSchemaValidAndFollowsTheRules() {
    var codec = new EventCodec();
    var validator = new EventValidator();
    List<GridEvent> events =
        Stream.concat(
                map("demanddata_2024-sample.csv", "2024-10-26", "2024-10-29").stream(),
                map("demanddataupdate_2026-sample.csv", "2026-03-28", "2026-03-31").stream())
            .toList();

    assertThat(events)
        .allSatisfy(
            e -> {
              assertThat(validator.validate(codec.toTree(e))).isEmpty();
              assertThat(EventRules.violations(e)).isEmpty();
            });
  }

  @Test
  void quotedFileFromTheLiveBackfill() {
    // Regression: the 2025 file double-quotes values ("2025-03-30"); parsing failed during the
    // first full backfill. 30 Mar 2025 was a 46-period day.
    List<GridEvent> events = map("demanddata_2025-sample.csv", "2025-03-30", "2025-03-31");

    assertThat(events).hasSize(46 * 3);
  }

  @Test
  void parsesBothDateFormats() {
    assertThat(NesoMapper.date("27-OCT-2024")).isEqualTo(LocalDate.parse("2024-10-27"));
    assertThat(NesoMapper.date("27-Oct-2024")).isEqualTo(LocalDate.parse("2024-10-27"));
    assertThat(NesoMapper.date("2026-03-29")).isEqualTo(LocalDate.parse("2026-03-29"));
  }

  private List<GridEvent> map(String file, String from, String to) {
    String csv =
        new String(FixtureServer.resource("/fixtures/neso/" + file), StandardCharsets.UTF_8);
    return mapper.historicDemand(csv, LocalDate.parse(from), LocalDate.parse(to), POLLED_AT);
  }
}
