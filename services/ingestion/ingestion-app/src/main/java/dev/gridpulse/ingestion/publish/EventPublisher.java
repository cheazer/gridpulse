package dev.gridpulse.ingestion.publish;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventRules;
import dev.gridpulse.events.EventType;
import dev.gridpulse.events.EventValidator;
import dev.gridpulse.events.GridEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Validates events and publishes them to their topic, keyed by region (ADR-0004, ADR-0006). Events
 * that fail validation go to the topic's dead-letter topic instead, with the reasons in a header,
 * so nothing invalid ever reaches a consumer.
 *
 * <p>Counts what Kafka acknowledged in {@code gridpulse.ingestion.events}, by {@code event_type}
 * and {@code outcome} ({@code published} or {@code dead-lettered}). A rising dead-letter count
 * means a source has changed shape.
 */
@Component
public class EventPublisher {

  public static final String EVENT_TYPE_HEADER = "event_type";
  public static final String SCHEMA_VERSION_HEADER = "schema_version";
  public static final String ERROR_HEADER = "gridpulse_error";

  private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

  private final KafkaTemplate<String, byte[]> kafka;
  private final EventCodec codec;
  private final EventValidator validator;
  private final MeterRegistry meters;

  public EventPublisher(
      KafkaTemplate<String, byte[]> kafka,
      EventCodec codec,
      EventValidator validator,
      MeterRegistry meters) {
    this.kafka = kafka;
    this.codec = codec;
    this.validator = validator;
    this.meters = meters;
  }

  public record PublishResult(int published, int deadLettered) {}

  /**
   * Sends every event and waits until Kafka has acknowledged all of them.
   *
   * @throws java.util.concurrent.CompletionException if any send fails; the caller's next poll
   *     re-fetches and re-sends, and idempotent IDs make that safe
   */
  public PublishResult publishAll(List<GridEvent> events) {
    List<CompletableFuture<?>> sends = new ArrayList<>(events.size());
    List<EventType> published = new ArrayList<>(events.size());
    List<EventType> rejected = new ArrayList<>();
    for (GridEvent event : events) {
      byte[] json = codec.encode(event);
      List<String> problems = problems(event, json);
      if (problems.isEmpty()) {
        sends.add(kafka.send(record(event.eventType().topic(), event, json)));
        published.add(event.eventType());
      } else {
        rejected.add(event.eventType());
        log.warn(
            "Dead-lettering {} event {}: {}",
            event.eventType().wireName(),
            event.eventId(),
            problems);
        ProducerRecord<String, byte[]> record =
            record(event.eventType().deadLetterTopic(), event, json);
        record.headers().add(ERROR_HEADER, String.join("; ", problems).getBytes(UTF_8));
        sends.add(kafka.send(record));
      }
    }
    CompletableFuture.allOf(sends.toArray(CompletableFuture<?>[]::new)).join();
    // Counted only once Kafka has acknowledged everything: a failed send counts nothing, and the
    // next poll re-sends it.
    published.forEach(type -> count(type, "published"));
    rejected.forEach(type -> count(type, "dead-lettered"));
    return new PublishResult(published.size(), rejected.size());
  }

  private void count(EventType type, String outcome) {
    meters
        .counter("gridpulse.ingestion.events", "event_type", type.wireName(), "outcome", outcome)
        .increment();
  }

  private List<String> problems(GridEvent event, byte[] json) {
    List<String> problems = new ArrayList<>(validator.validate(codec.readTree(json)));
    EventRules.violations(event).forEach((check, message) -> problems.add(check + ": " + message));
    return problems;
  }

  private static ProducerRecord<String, byte[]> record(String topic, GridEvent event, byte[] json) {
    var record = new ProducerRecord<>(topic, event.partitionKey(), json);
    record.headers().add(EVENT_TYPE_HEADER, event.eventType().wireName().getBytes(UTF_8));
    record.headers().add(SCHEMA_VERSION_HEADER, event.schemaVersion().getBytes(UTF_8));
    return record;
  }
}
