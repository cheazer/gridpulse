package dev.gridpulse.events;

import dev.gridpulse.events.payload.Payload;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts {@link GridEvent}s to and from their JSON wire form. Thread-safe.
 *
 * <p>Decoding is deliberately lenient about unknown fields (ADR-0002): a consumer built against
 * schema 1.0 must still read events from a 1.1 producer that added an optional field. Strictness
 * lives in {@link EventValidator}, which producers run before publishing.
 */
public final class EventCodec {

  private final JsonMapper mapper =
      JsonMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  /**
   * @throws tools.jackson.core.JacksonException if the JSON is malformed or doesn't fit the model
   * @throws IllegalArgumentException if the payload breaks a payload invariant
   */
  public GridEvent decode(byte[] json) {
    return fromWire(mapper.readValue(json, WireEvent.class));
  }

  public GridEvent decode(String json) {
    return fromWire(mapper.readValue(json, WireEvent.class));
  }

  public byte[] encode(GridEvent event) {
    return mapper.writeValueAsBytes(event);
  }

  public JsonNode toTree(GridEvent event) {
    return mapper.valueToTree(event);
  }

  public JsonNode readTree(byte[] json) {
    return mapper.readTree(json);
  }

  public JsonNode readTree(String json) {
    return mapper.readTree(json);
  }

  private GridEvent fromWire(WireEvent wire) {
    Payload payload = mapper.treeToValue(wire.payload(), wire.eventType().payloadType());
    return new GridEvent(
        wire.eventId(),
        wire.schemaVersion(),
        wire.eventType(),
        wire.source(),
        wire.series(),
        wire.region(),
        wire.validFrom(),
        wire.validTo(),
        wire.settlementDate(),
        wire.settlementPeriod(),
        wire.issuedAt(),
        wire.ingestedAt(),
        payload);
  }

  /** The envelope with the payload left as a tree until we know which type it is. */
  private record WireEvent(
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
      JsonNode payload) {}
}
