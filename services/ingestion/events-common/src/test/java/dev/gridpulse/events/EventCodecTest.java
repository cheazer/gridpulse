package dev.gridpulse.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.gridpulse.events.payload.Demand;
import dev.gridpulse.events.payload.Demand.DemandType;
import dev.gridpulse.events.payload.SystemPrice;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class EventCodecTest {

  private final EventCodec codec = new EventCodec();
  private final EventValidator validator = new EventValidator();

  private final GridEvent demand =
      GridEvent.create(
          EventType.DEMAND,
          Source.ELEXON_INSIGHTS,
          "indo",
          Region.GB,
          Instant.parse("2026-10-25T23:30:00Z"),
          Instant.parse("2026-10-26T00:00:00Z"),
          SettlementPeriods.of(Instant.parse("2026-10-25T23:30:00Z")),
          null,
          Instant.parse("2026-10-26T00:20:41Z"),
          new Demand(DemandType.INDO, 24315));

  @Test
  void encodedEventIsSchemaValidAndDecodesBackToItself() {
    byte[] json = codec.encode(demand);
    assertThat(validator.validate(codec.readTree(json))).isEmpty();
    assertThat(codec.decode(json)).isEqualTo(demand);
  }

  @Test
  void consumerToleratesFieldsAddedByANewerProducerButTheValidatorDoesNot() {
    ObjectNode newer = (ObjectNode) codec.toTree(demand);
    ((ObjectNode) newer.get("payload")).put("embedded_solar_mw", 1200);

    assertThat(codec.decode(newer.toString())).isEqualTo(demand);
    assertThat(validator.validate(newer)).isNotEmpty();
  }

  @Test
  void payloadMustMatchTheEventType() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                GridEvent.create(
                    EventType.DEMAND,
                    Source.ELEXON_INSIGHTS,
                    "system-price",
                    Region.GB,
                    Instant.parse("2026-10-27T18:00:00Z"),
                    Instant.parse("2026-10-27T18:30:00Z"),
                    null,
                    null,
                    Instant.parse("2026-10-27T19:05:00Z"),
                    new SystemPrice(new BigDecimal("92.40"), new BigDecimal("92.40"), null)))
        .withMessageContaining("demand event cannot carry a SystemPrice");
  }

  @Test
  void unknownEventTypeIsReportedNotThrown() {
    ObjectNode unknown = (ObjectNode) codec.toTree(demand);
    unknown.put("event_type", "interconnector_flow");
    assertThat(validator.validate(unknown))
        .containsExactly("unknown event_type: interconnector_flow");
  }

  @Test
  void eventKnowsItsTopicsAndKey() {
    assertThat(demand.eventType().topic()).isEqualTo("gridpulse.demand.v1");
    assertThat(demand.eventType().deadLetterTopic()).isEqualTo("gridpulse.demand.v1.dlq");
    assertThat(demand.partitionKey()).isEqualTo("GB");
    assertThat(demand.isForecast()).isFalse();
  }
}
