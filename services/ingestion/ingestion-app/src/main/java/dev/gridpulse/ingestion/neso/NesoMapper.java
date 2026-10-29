package dev.gridpulse.ingestion.neso;

import dev.gridpulse.events.EventType;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.Region;
import dev.gridpulse.events.SettlementPeriod;
import dev.gridpulse.events.SettlementPeriods;
import dev.gridpulse.events.Source;
import dev.gridpulse.events.payload.Demand;
import dev.gridpulse.events.payload.Demand.DemandType;
import dev.gridpulse.events.payload.GenerationMix;
import dev.gridpulse.events.payload.GenerationMix.Fuel;
import dev.gridpulse.events.payload.GenerationMix.FuelReading;
import dev.gridpulse.events.payload.Payload;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps NESO historic demand CSV rows to events: settled National Demand (ND), Transmission System
 * Demand (TSD), and embedded (distribution-connected) solar and wind, which Elexon's FUELHH lacks.
 *
 * <p>The yearly files aren't consistent: 2024 writes dates as {@code 01-JAN-2024} and has no {@code
 * FORECAST_ACTUAL_INDICATOR} column; 2026 uses ISO dates and marks each row actual or forecast.
 * Both are accepted; rows marked as forecasts are skipped.
 */
@Component
public class NesoMapper {

  private static final Logger log = LoggerFactory.getLogger(NesoMapper.class);

  private static final DateTimeFormatter OLD_DATE =
      new DateTimeFormatterBuilder()
          .parseCaseInsensitive()
          .appendPattern("dd-MMM-uuuu")
          .toFormatter(Locale.ENGLISH);

  /**
   * Events for rows with a settlement date in {@code from} (inclusive) to {@code to} (exclusive).
   */
  public List<GridEvent> historicDemand(
      String csv, LocalDate from, LocalDate to, Instant polledAt) {
    String[] lines = csv.strip().split("\\R");
    Map<String, Integer> column = header(lines[0]);
    Integer indicator = column.get("FORECAST_ACTUAL_INDICATOR");
    List<GridEvent> events = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) {
      String[] cells = lines[i].split(",", -1);
      for (int c = 0; c < cells.length; c++) {
        cells[c] = unquote(cells[c]); // the 2025 file quotes every value
      }
      LocalDate date = date(cells[column.get("SETTLEMENT_DATE")]);
      if (date.isBefore(from) || !date.isBefore(to)) {
        continue;
      }
      if (indicator != null && !cells[indicator].strip().equals("A")) {
        continue; // a forecast row in the current year's file
      }
      SettlementPeriod settlement;
      try {
        settlement =
            new SettlementPeriod(
                date, Integer.parseInt(cells[column.get("SETTLEMENT_PERIOD")].strip()));
      } catch (IllegalArgumentException e) {
        log.warn("Skipping NESO row {}: {}", lines[i], e.getMessage());
        continue;
      }
      Instant start = SettlementPeriods.start(settlement);
      Double nd = number(cells, column, "ND");
      Double tsd = number(cells, column, "TSD");
      Double solar = number(cells, column, "EMBEDDED_SOLAR_GENERATION");
      Double wind = number(cells, column, "EMBEDDED_WIND_GENERATION");
      if (nd != null) {
        events.add(
            event(
                EventType.DEMAND,
                "nd",
                start,
                settlement,
                polledAt,
                new Demand(DemandType.ND, nd)));
      }
      if (tsd != null) {
        events.add(
            event(
                EventType.DEMAND,
                "tsd",
                start,
                settlement,
                polledAt,
                new Demand(DemandType.TSD, tsd)));
      }
      List<FuelReading> embedded = new ArrayList<>();
      if (solar != null) {
        embedded.add(new FuelReading(Fuel.SOLAR, "EMBEDDED_SOLAR_GENERATION", solar));
      }
      if (wind != null) {
        embedded.add(new FuelReading(Fuel.WIND, "EMBEDDED_WIND_GENERATION", wind));
      }
      if (!embedded.isEmpty()) {
        events.add(
            event(
                EventType.GENERATION_MIX,
                "embedded",
                start,
                settlement,
                polledAt,
                new GenerationMix(GenerationMix.Unit.MW, embedded)));
      }
    }
    return events;
  }

  static LocalDate date(String cell) {
    String value = cell.strip();
    return Character.isDigit(value.charAt(2))
        ? LocalDate.parse(value)
        : LocalDate.parse(value, OLD_DATE);
  }

  private static Map<String, Integer> header(String line) {
    Map<String, Integer> column = new HashMap<>();
    String[] names = line.split(",");
    for (int i = 0; i < names.length; i++) {
      column.put(unquote(names[i]), i);
    }
    return column;
  }

  /** Strips whitespace and one pair of surrounding double quotes: {@code "2025-01-01"}. */
  static String unquote(String cell) {
    String value = cell.strip();
    return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
        ? value.substring(1, value.length() - 1).strip()
        : value;
  }

  private static Double number(String[] cells, Map<String, Integer> column, String name) {
    Integer index = column.get(name);
    if (index == null || index >= cells.length || cells[index].isBlank()) {
      return null;
    }
    return Double.parseDouble(cells[index].strip());
  }

  private static GridEvent event(
      EventType type,
      String series,
      Instant start,
      SettlementPeriod settlement,
      Instant polledAt,
      Payload payload) {
    return GridEvent.create(
        type,
        Source.NESO_DATA_PORTAL,
        series,
        Region.GB,
        start,
        start.plus(SettlementPeriods.PERIOD_LENGTH),
        settlement,
        null,
        polledAt.truncatedTo(ChronoUnit.MILLIS),
        payload);
  }
}
