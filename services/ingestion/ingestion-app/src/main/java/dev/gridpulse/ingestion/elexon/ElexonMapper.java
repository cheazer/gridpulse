package dev.gridpulse.ingestion.elexon;

import static java.util.Map.entry;

import dev.gridpulse.events.EventType;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.Region;
import dev.gridpulse.events.SettlementPeriod;
import dev.gridpulse.events.SettlementPeriods;
import dev.gridpulse.events.Source;
import dev.gridpulse.events.payload.Demand;
import dev.gridpulse.events.payload.Demand.DemandType;
import dev.gridpulse.events.payload.DemandForecast;
import dev.gridpulse.events.payload.GenerationMix;
import dev.gridpulse.events.payload.GenerationMix.Fuel;
import dev.gridpulse.events.payload.GenerationMix.FuelReading;
import dev.gridpulse.events.payload.Payload;
import dev.gridpulse.ingestion.elexon.ElexonApi.DemandOutturn;
import dev.gridpulse.ingestion.elexon.ElexonApi.FuelGeneration;
import dev.gridpulse.ingestion.elexon.ElexonClient.DayAheadProduct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns Elexon Insights responses into events (ADR-0007). Pure apart from logging: the poll time is
 * passed in, so the same input always gives the same events.
 */
@Component
public class ElexonMapper {

  private static final Logger log = LoggerFactory.getLogger(ElexonMapper.class);

  /** BM fuel types. Interconnectors (INTFR, INTNSL, ...) are matched by prefix in {@link #fuel}. */
  private static final Map<String, Fuel> FUELS =
      Map.ofEntries(
          entry("CCGT", Fuel.GAS),
          entry("OCGT", Fuel.GAS),
          entry("COAL", Fuel.COAL),
          entry("OIL", Fuel.OIL),
          entry("NUCLEAR", Fuel.NUCLEAR),
          entry("WIND", Fuel.WIND),
          entry("NPSHYD", Fuel.HYDRO),
          entry("PS", Fuel.STORAGE),
          entry("BIOMASS", Fuel.BIOMASS),
          entry("OTHER", Fuel.OTHER));

  /** INDO and ITSDO: two demand events per settlement period. */
  public List<GridEvent> demandOutturn(List<DemandOutturn> rows, Instant polledAt) {
    List<GridEvent> events = new ArrayList<>();
    for (DemandOutturn row : rows) {
      SettlementPeriod settlement =
          settlement(row.startTime(), row.settlementDate(), row.settlementPeriod());
      if (settlement == null) {
        continue;
      }
      if (row.initialDemandOutturn() != null) {
        events.add(
            actual(
                EventType.DEMAND,
                "indo",
                row.startTime(),
                settlement,
                polledAt,
                new Demand(DemandType.INDO, row.initialDemandOutturn())));
      }
      if (row.initialTransmissionSystemDemandOutturn() != null) {
        events.add(
            actual(
                EventType.DEMAND,
                "itsdo",
                row.startTime(),
                settlement,
                polledAt,
                new Demand(DemandType.ITSDO, row.initialTransmissionSystemDemandOutturn())));
      }
    }
    return events;
  }

  /** FUELHH: one generation-mix event per settlement period, one reading per fuel code. */
  public List<GridEvent> fuelMix(List<FuelGeneration> rows, Instant polledAt) {
    TreeMap<Instant, List<FuelGeneration>> byPeriod = new TreeMap<>();
    for (FuelGeneration row : rows) {
      byPeriod.computeIfAbsent(row.startTime(), start -> new ArrayList<>()).add(row);
    }
    List<GridEvent> events = new ArrayList<>();
    byPeriod.forEach(
        (start, periodRows) -> {
          FuelGeneration first = periodRows.getFirst();
          SettlementPeriod settlement =
              settlement(start, first.settlementDate(), first.settlementPeriod());
          if (settlement == null) {
            return;
          }
          try {
            List<FuelReading> readings =
                periodRows.stream()
                    .filter(row -> row.generation() != null)
                    .map(
                        row ->
                            new FuelReading(fuel(row.fuelType()), row.fuelType(), row.generation()))
                    .toList();
            events.add(
                actual(
                    EventType.GENERATION_MIX,
                    "fuelhh",
                    start,
                    settlement,
                    polledAt,
                    new GenerationMix(GenerationMix.Unit.MW, readings)));
          } catch (IllegalArgumentException e) {
            // e.g. negative wind: impossible data. Skip the period; the next poll may have it
            // fixed.
            log.warn("Skipping FUELHH period starting {}: {}", start, e.getMessage());
          }
        });
    return events;
  }

  /**
   * Imbalance prices, rounded to the precision the database stores (2 dp, NIV 3 dp) so the event
   * and the row agree.
   */
  public List<GridEvent> systemPrices(List<ElexonApi.SystemPrice> rows, Instant polledAt) {
    List<GridEvent> events = new ArrayList<>();
    for (ElexonApi.SystemPrice row : rows) {
      SettlementPeriod settlement =
          settlement(row.startTime(), row.settlementDate(), row.settlementPeriod());
      if (settlement == null || row.systemSellPrice() == null || row.systemBuyPrice() == null) {
        continue;
      }
      events.add(
          actual(
              EventType.SYSTEM_PRICE,
              "system-price",
              row.startTime(),
              settlement,
              polledAt,
              new dev.gridpulse.events.payload.SystemPrice(
                  money(row.systemSellPrice()),
                  money(row.systemBuyPrice()),
                  row.netImbalanceVolume() == null
                      ? null
                      : row.netImbalanceVolume().setScale(3, RoundingMode.HALF_UP))));
    }
    return events;
  }

  /**
   * One forecast event per period. {@code issued_at} is NESO's publish time, so every revision is
   * its own event without any bucketing.
   */
  public List<GridEvent> dayAheadForecast(
      List<ElexonApi.DemandForecast> rows, DayAheadProduct product, Instant polledAt) {
    DemandType forecasts = product == DayAheadProduct.NDF ? DemandType.ND : DemandType.TSD;
    List<GridEvent> events = new ArrayList<>();
    for (ElexonApi.DemandForecast row : rows) {
      boolean national = row.boundary() == null || row.boundary().equals("N");
      if (!national || row.demand() == null || row.publishTime() == null) {
        continue;
      }
      SettlementPeriod settlement =
          settlement(row.startTime(), row.settlementDate(), row.settlementPeriod());
      if (settlement == null) {
        continue;
      }
      events.add(
          event(
              EventType.DEMAND_FORECAST,
              product.name().toLowerCase(Locale.ROOT),
              row.startTime(),
              settlement,
              row.publishTime().truncatedTo(ChronoUnit.SECONDS),
              polledAt,
              new DemandForecast(forecasts, row.demand(), product.name())));
    }
    return events;
  }

  static Fuel fuel(String code) {
    if (code.startsWith("INT")) {
      return Fuel.IMPORTS;
    }
    Fuel fuel = FUELS.get(code);
    if (fuel == null) {
      log.warn("Unknown FUELHH fuel type {}; recording it as other", code);
      return Fuel.OTHER;
    }
    return fuel;
  }

  /**
   * Our settlement period for {@code start}, cross-checked against Elexon's. A disagreement means
   * one side has the clock-change maths wrong, so the row is skipped rather than stored under the
   * wrong period.
   */
  private static SettlementPeriod settlement(Instant start, LocalDate date, int period) {
    if (!SettlementPeriods.isAligned(start)) {
      log.warn("Skipping Elexon row not on a half-hour boundary: {}", start);
      return null;
    }
    SettlementPeriod ours = SettlementPeriods.of(start);
    if (!ours.date().equals(date) || ours.period() != period) {
      log.warn("Skipping Elexon row {}: Elexon says {} SP{}, we say {}", start, date, period, ours);
      return null;
    }
    return ours;
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private static GridEvent actual(
      EventType type,
      String series,
      Instant start,
      SettlementPeriod settlement,
      Instant polledAt,
      Payload payload) {
    return event(type, series, start, settlement, null, polledAt, payload);
  }

  private static GridEvent event(
      EventType type,
      String series,
      Instant start,
      SettlementPeriod settlement,
      Instant issuedAt,
      Instant polledAt,
      Payload payload) {
    return GridEvent.create(
        type,
        Source.ELEXON_INSIGHTS,
        series,
        Region.GB,
        start,
        start.plus(SettlementPeriods.PERIOD_LENGTH),
        settlement,
        issuedAt,
        polledAt.truncatedTo(ChronoUnit.MILLIS),
        payload);
  }
}
