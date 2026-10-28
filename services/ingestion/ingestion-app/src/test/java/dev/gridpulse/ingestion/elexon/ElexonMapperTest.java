package dev.gridpulse.ingestion.elexon;

import static dev.gridpulse.ingestion.elexon.ElexonFixtures.POLLED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventRules;
import dev.gridpulse.events.EventValidator;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.payload.Demand;
import dev.gridpulse.events.payload.Demand.DemandType;
import dev.gridpulse.events.payload.DemandForecast;
import dev.gridpulse.events.payload.GenerationMix;
import dev.gridpulse.events.payload.GenerationMix.Fuel;
import dev.gridpulse.events.payload.SystemPrice;
import dev.gridpulse.ingestion.elexon.ElexonApi.DemandOutturn;
import dev.gridpulse.ingestion.elexon.ElexonClient.DayAheadProduct;
import dev.gridpulse.ingestion.testing.FixtureServer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Runs the recorded Elexon responses through the mapper. Poll time: 2026-10-28T21:10:01Z. */
class ElexonMapperTest {

  private final ElexonMapper mapper = new ElexonMapper();

  @Test
  void eachOutturnPeriodBecomesAnIndoAndAnItsdoActual() {
    List<GridEvent> events = mapper.demandOutturn(ElexonFixtures.demandOutturn(), POLLED_AT);

    assertThat(events).hasSize(2 * 90);
    assertThat(events).allSatisfy(e -> assertThat(e.isForecast()).isFalse());
    assertThat(events.subList(0, 2))
        .extracting(GridEvent::series, e -> ((Demand) e.payload()).demandMw())
        .containsExactly(tuple("indo", 23001.0), tuple("itsdo", 28414.0));
    assertThat(events.getFirst().settlementPeriod()).isEqualTo(1);
    assertThat(((Demand) events.getFirst().payload()).demandType()).isEqualTo(DemandType.INDO);
  }

  @Test
  void fuelMixIsOneEventPerPeriodWithEveryFuelCodeAndNetFlows() {
    List<GridEvent> events = mapper.fuelMix(ElexonFixtures.fuelGeneration(), POLLED_AT);

    assertThat(events).hasSize(90);
    assertThat(events)
        .allSatisfy(e -> assertThat(((GenerationMix) e.payload()).fuels()).hasSize(20));
    assertThat(events)
        .flatMap(e -> ((GenerationMix) e.payload()).fuels())
        .anySatisfy(
            fuel -> {
              assertThat(fuel.fuel()).isEqualTo(Fuel.IMPORTS);
              assertThat(fuel.value()).isNegative(); // GB exporting
            });
  }

  @Test
  void fuelMixMatchesTheGoldenNetFlowsExampleExactly() {
    var codec = new EventCodec();
    GridEvent golden =
        codec.decode(
            FixtureServer.resource("/gridpulse/examples/v1/valid/generation-mix-net-flows.json"));

    GridEvent mapped =
        mapper.fuelMix(ElexonFixtures.fuelGeneration(), POLLED_AT).stream()
            .filter(e -> e.validFrom().equals(golden.validFrom()))
            .findFirst()
            .orElseThrow();

    assertThat(mapped).usingRecursiveComparison().ignoringCollectionOrder().isEqualTo(golden);
  }

  @Test
  void systemPricesAreRoundedToTheStoredPrecision() {
    List<GridEvent> events =
        mapper.systemPrices(ElexonFixtures.systemPrices("2026-10-27"), POLLED_AT);

    assertThat(events).hasSize(48);
    SystemPrice first = (SystemPrice) events.getFirst().payload();
    assertThat(first.systemSellPriceGbpPerMwh()).isEqualTo(new BigDecimal("16.98")); // 16.97897...
    assertThat(first.netImbalanceVolumeMwh())
        .isEqualTo(new BigDecimal("-216.862")); // -216.86169...
  }

  @Test
  void dayAheadForecastsUseNesosPublishTimeAndTheMatchingDemandDefinition() {
    List<GridEvent> ndf =
        mapper.dayAheadForecast(ElexonFixtures.forecast("ndf"), DayAheadProduct.NDF, POLLED_AT);
    List<GridEvent> tsdf =
        mapper.dayAheadForecast(ElexonFixtures.forecast("tsdf"), DayAheadProduct.TSDF, POLLED_AT);

    assertThat(ndf).hasSize(64);
    assertThat(ndf)
        .extracting(GridEvent::issuedAt)
        .containsOnly(Instant.parse("2026-10-28T20:47:00Z"));
    assertThat(ndf)
        .extracting(e -> (DemandForecast) e.payload())
        .allSatisfy(
            f -> {
              assertThat(f.demandType()).isEqualTo(DemandType.ND); // ADR-0007: NDF forecasts ND
              assertThat(f.product()).isEqualTo("NDF");
            });
    assertThat(tsdf)
        .extracting(e -> ((DemandForecast) e.payload()).demandType())
        .containsOnly(DemandType.TSD);
    assertThat(ndf).extracting(GridEvent::series).containsOnly("ndf");
  }

  @Test
  void everyMappedEventIsSchemaValidAndFollowsTheRules() {
    var codec = new EventCodec();
    var validator = new EventValidator();
    List<GridEvent> events =
        Stream.of(
                mapper.demandOutturn(ElexonFixtures.demandOutturn(), POLLED_AT),
                mapper.fuelMix(ElexonFixtures.fuelGeneration(), POLLED_AT),
                mapper.systemPrices(ElexonFixtures.systemPrices("2026-10-27"), POLLED_AT),
                mapper.systemPrices(ElexonFixtures.systemPrices("2026-10-28"), POLLED_AT),
                mapper.dayAheadForecast(
                    ElexonFixtures.forecast("ndf"), DayAheadProduct.NDF, POLLED_AT),
                mapper.dayAheadForecast(
                    ElexonFixtures.forecast("tsdf"), DayAheadProduct.TSDF, POLLED_AT))
            .flatMap(List::stream)
            .toList();

    assertThat(events).hasSize(180 + 90 + 48 + 41 + 64 + 64);
    assertThat(events)
        .allSatisfy(
            e -> {
              assertThat(validator.validate(codec.toTree(e))).isEmpty();
              assertThat(EventRules.violations(e)).isEmpty();
            });
  }

  @Test
  void rowsWhoseSettlementPeriodDisagreesWithOursAreSkipped() {
    var row =
        new DemandOutturn(
            Instant.parse("2026-10-27T18:00:00Z"),
            LocalDate.parse("2026-10-27"),
            36,
            30000.0,
            32000.0);

    assertThat(mapper.demandOutturn(List.of(row), POLLED_AT)).isEmpty(); // 18:00Z is SP37
  }

  @Test
  void interconnectorsMapToImportsAndUnknownCodesToOther() {
    assertThat(ElexonMapper.fuel("INTNEW")).isEqualTo(Fuel.IMPORTS);
    assertThat(ElexonMapper.fuel("PS")).isEqualTo(Fuel.STORAGE);
    assertThat(ElexonMapper.fuel("FUSION")).isEqualTo(Fuel.OTHER);
  }
}
