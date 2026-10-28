package dev.gridpulse.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.gridpulse.events.payload.Payload;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One GridPulse event: the shared envelope plus a typed payload (schemas/events/v1).
 *
 * <p>The constructor only enforces what the type system can't: required fields and a payload that
 * matches the event type. Data rules (ID derivation, settlement periods) are checked by {@link
 * EventRules}, so a consumer can still decode a bad event and route it to the dead-letter topic.
 *
 * @param settlementDate null for data not aligned to settlement periods (e.g. hourly weather)
 * @param settlementPeriod null exactly when {@code settlementDate} is null
 * @param issuedAt when a forecast was issued; null for actuals and observations
 */
public record GridEvent(
    UUID eventId,
    String schemaVersion,
    EventType eventType,
    Source source,
    String series,
    Region region,
    Instant validFrom,
    Instant validTo,
    LocalDate settlementDate,
    Integer settlementPeriod,
    Instant issuedAt,
    Instant ingestedAt,
    Payload payload) {

  /** The schema version producers in this build write. */
  public static final String SCHEMA_VERSION = "1.0.0";

  public GridEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(series, "series");
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(validFrom, "validFrom");
    Objects.requireNonNull(validTo, "validTo");
    Objects.requireNonNull(ingestedAt, "ingestedAt");
    Objects.requireNonNull(payload, "payload");
    if (!eventType.payloadType().isInstance(payload)) {
      throw new IllegalArgumentException(
          eventType.wireName() + " event cannot carry a " + payload.getClass().getSimpleName());
    }
  }

  /**
   * Creates an event with its ID derived from the identity fields (ADR-0004), so the same record
   * fetched twice always produces the same ID.
   *
   * @param settlement the settlement period, or null for data not aligned to settlement periods
   */
  public static GridEvent create(
      EventType eventType,
      Source source,
      String series,
      Region region,
      Instant validFrom,
      Instant validTo,
      SettlementPeriod settlement,
      Instant issuedAt,
      Instant ingestedAt,
      Payload payload) {
    return new GridEvent(
        EventIds.derive(source, eventType, series, region, validFrom, issuedAt),
        SCHEMA_VERSION,
        eventType,
        source,
        series,
        region,
        validFrom,
        validTo,
        settlement == null ? null : settlement.date(),
        settlement == null ? null : settlement.period(),
        issuedAt,
        ingestedAt,
        payload);
  }

  /** The Kafka message key (ADR-0004). */
  public String partitionKey() {
    return region.slug();
  }

  @JsonIgnore // derived; without this Jackson would serialise it as a "forecast" property
  public boolean isForecast() {
    return issuedAt != null;
  }
}
