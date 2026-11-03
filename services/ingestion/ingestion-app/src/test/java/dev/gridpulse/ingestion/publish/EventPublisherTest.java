package dev.gridpulse.ingestion.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventValidator;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.ingestion.testing.FixtureServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/** The publisher's metrics, against a Kafka template that acknowledges (or fails) every send. */
class EventPublisherTest {

  private final EventCodec codec = new EventCodec();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);

  private final EventPublisher publisher =
      new EventPublisher(kafka, codec, new EventValidator(), meters);

  @Test
  @SuppressWarnings("unchecked")
  void countsWhatKafkaAcknowledgedByTypeAndOutcome() {
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    GridEvent valid = example("carbon-intensity-national-actual.json");

    var result = publisher.publishAll(List.of(valid, valid, withWrongId(valid)));

    assertThat(result).isEqualTo(new EventPublisher.PublishResult(2, 1));
    assertThat(count("carbon_intensity", "published")).isEqualTo(2);
    assertThat(count("carbon_intensity", "dead-lettered")).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void countsNothingWhenASendFails() {
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    assertThatThrownBy(
        () -> publisher.publishAll(List.of(example("carbon-intensity-national-actual.json"))));

    assertThat(meters.find("gridpulse.ingestion.events").counters()).isEmpty();
  }

  private double count(String eventType, String outcome) {
    return meters
        .get("gridpulse.ingestion.events")
        .tag("event_type", eventType)
        .tag("outcome", outcome)
        .counter()
        .count();
  }

  private GridEvent example(String name) {
    return codec.decode(FixtureServer.resource("/gridpulse/examples/v1/valid/" + name));
  }

  /** The same event under an ID its fields don't derive to: EventRules sends it to the DLQ. */
  private static GridEvent withWrongId(GridEvent e) {
    return new GridEvent(
        UUID.randomUUID(),
        e.schemaVersion(),
        e.eventType(),
        e.source(),
        e.series(),
        e.region(),
        e.validFrom(),
        e.validTo(),
        e.settlementDate(),
        e.settlementPeriod(),
        e.issuedAt(),
        e.ingestedAt(),
        e.payload());
  }
}
