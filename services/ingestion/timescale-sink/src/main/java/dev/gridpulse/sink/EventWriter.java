package dev.gridpulse.sink;

import dev.gridpulse.events.EventType;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.payload.CarbonIntensity;
import dev.gridpulse.events.payload.Demand;
import dev.gridpulse.events.payload.DemandForecast;
import dev.gridpulse.events.payload.GenerationMix;
import dev.gridpulse.events.payload.SystemPrice;
import dev.gridpulse.events.payload.Weather;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upserts events into the typed hypertables (ADR-0005). Re-delivered events update their existing
 * row instead of adding one, which is what makes at-least-once delivery safe.
 */
@Repository
public class EventWriter {

  private static final List<String> ENVELOPE =
      List.of(
          "event_id",
          "valid_from",
          "valid_to",
          "settlement_date",
          "settlement_period",
          "issued_at",
          "ingested_at",
          "source",
          "series",
          "region",
          "schema_version");

  private static final List<String> KEY = List.of("event_id", "valid_from");

  private static final Map<EventType, String> UPSERTS = new EnumMap<>(EventType.class);

  static {
    UPSERTS.put(
        EventType.CARBON_INTENSITY,
        upsert(
            "carbon_intensity",
            KEY,
            "forecast_gco2_per_kwh",
            "actual_gco2_per_kwh",
            "intensity_index"));
    UPSERTS.put(
        EventType.GENERATION_MIX,
        upsert(
            "generation_mix",
            List.of("event_id", "source_fuel_code", "valid_from"),
            "unit",
            "fuel",
            "source_fuel_code",
            "value"));
    UPSERTS.put(EventType.DEMAND, upsert("demand", KEY, "demand_type", "demand_mw"));
    UPSERTS.put(
        EventType.DEMAND_FORECAST,
        upsert("demand_forecast", KEY, "demand_type", "forecast_mw", "product"));
    UPSERTS.put(
        EventType.SYSTEM_PRICE,
        upsert(
            "system_price",
            KEY,
            "system_sell_price_gbp_per_mwh",
            "system_buy_price_gbp_per_mwh",
            "net_imbalance_volume_mwh"));
    UPSERTS.put(
        EventType.WEATHER,
        upsert(
            "weather",
            KEY,
            "latitude",
            "longitude",
            "temperature_2m_c",
            "wind_speed_10m_ms",
            "wind_speed_100m_ms",
            "shortwave_radiation_wm2",
            "cloud_cover_pct"));
  }

  private static final String DELETE_GENERATION_MIX =
      "DELETE FROM grid.generation_mix WHERE event_id = ? AND valid_from = ?";

  private final JdbcTemplate jdbc;

  public EventWriter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Writes the whole batch in one transaction: all of it lands, or none of it. */
  @Transactional
  public void write(List<GridEvent> events) {
    Map<EventType, List<GridEvent>> byType =
        events.stream()
            .collect(
                Collectors.groupingBy(
                    GridEvent::eventType,
                    () -> new EnumMap<>(EventType.class),
                    Collectors.toList()));
    byType.forEach(
        (type, batch) -> {
          if (type == EventType.GENERATION_MIX) {
            // A corrected record may list fewer fuels, so replace the event's rows rather than
            // upserting into them; otherwise a dropped fuel would linger.
            jdbc.batchUpdate(
                DELETE_GENERATION_MIX,
                batch.stream().map(e -> new Object[] {e.eventId(), utc(e.validFrom())}).toList());
          }
          jdbc.batchUpdate(UPSERTS.get(type), batch.stream().flatMap(EventWriter::rows).toList());
        });
  }

  private static Stream<Object[]> rows(GridEvent event) {
    // Payload is sealed: adding an event type without a case here is a compile error.
    // Stream.<Object[]>of because Stream.of(array) would stream the row's cells as varargs.
    return switch (event.payload()) {
      case CarbonIntensity ci ->
          Stream.<Object[]>of(
              row(
                  event,
                  ci.forecastGco2PerKwh(),
                  ci.actualGco2PerKwh(),
                  ci.index() == null ? null : ci.index().wireName()));
      case GenerationMix mix ->
          mix.fuels().stream()
              .map(
                  fuel ->
                      row(
                          event,
                          mix.unit().wireName(),
                          fuel.fuel().wireName(),
                          fuel.sourceFuelCode(),
                          fuel.value()));
      case Demand demand ->
          Stream.<Object[]>of(row(event, demand.demandType().name(), demand.demandMw()));
      case DemandForecast forecast ->
          Stream.<Object[]>of(
              row(event, forecast.demandType().name(), forecast.forecastMw(), forecast.product()));
      case SystemPrice price ->
          Stream.<Object[]>of(
              row(
                  event,
                  price.systemSellPriceGbpPerMwh(),
                  price.systemBuyPriceGbpPerMwh(),
                  price.netImbalanceVolumeMwh()));
      case Weather weather ->
          Stream.<Object[]>of(
              row(
                  event,
                  weather.location().latitude(),
                  weather.location().longitude(),
                  weather.temperature2mC(),
                  weather.windSpeed10mMs(),
                  weather.windSpeed100mMs(),
                  weather.shortwaveRadiationWm2(),
                  weather.cloudCoverPct()));
    };
  }

  /** Envelope values in {@link #ENVELOPE} order, followed by the payload values. */
  private static Object[] row(GridEvent e, Object... payload) {
    List<Object> values = new ArrayList<>(ENVELOPE.size() + payload.length);
    values.add(e.eventId());
    values.add(utc(e.validFrom()));
    values.add(utc(e.validTo()));
    values.add(e.settlementDate());
    values.add(e.settlementPeriod());
    values.add(utc(e.issuedAt()));
    values.add(utc(e.ingestedAt()));
    values.add(e.source().wireName());
    values.add(e.series());
    values.add(e.region().slug());
    values.add(e.schemaVersion());
    values.addAll(Arrays.asList(payload));
    return values.toArray();
  }

  /**
   * {@code INSERT ... ON CONFLICT (key) DO UPDATE} setting every non-key column from the incoming
   * row, so a corrected record replaces the old values.
   */
  private static String upsert(String table, List<String> conflictKey, String... payloadColumns) {
    List<String> columns = new ArrayList<>(ENVELOPE);
    for (String column : payloadColumns) {
      if (!columns.contains(column)) {
        columns.add(column);
      }
    }
    String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
    String updates =
        String.join(
            ", ",
            columns.stream()
                .filter(c -> !conflictKey.contains(c))
                .map(c -> c + " = EXCLUDED." + c)
                .toList());
    return "INSERT INTO grid.%s (%s) VALUES (%s) ON CONFLICT (%s) DO UPDATE SET %s"
        .formatted(
            table,
            String.join(", ", columns),
            placeholders,
            String.join(", ", conflictKey),
            updates);
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
