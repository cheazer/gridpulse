package dev.gridpulse.sink;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.GridEvent;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

/**
 * Consumes every event topic in batches and writes each batch in one transaction. Offsets are
 * committed only after the listener returns, i.e. after the database commit (ADR-0006).
 *
 * <p>Each batch is observed as {@code gridpulse.sink.batch}: a span with the batch size, and a
 * timer. Spring Kafka's own observation covers record listeners only, not batch ones, so a batch's
 * span starts a trace of its own rather than continuing the producer's.
 */
@Component
public class EventConsumer {

  private final EventCodec codec;
  private final EventWriter writer;
  private final AggregateRefresher aggregates;
  private final SinkMetrics metrics;
  private final ObservationRegistry observations;

  public EventConsumer(
      EventCodec codec,
      EventWriter writer,
      AggregateRefresher aggregates,
      SinkMetrics metrics,
      ObservationRegistry observations) {
    this.codec = codec;
    this.writer = writer;
    this.aggregates = aggregates;
    this.metrics = metrics;
    this.observations = observations;
  }

  @KafkaListener(topics = "#{T(dev.gridpulse.events.EventType).allTopics()}")
  public void consume(List<ConsumerRecord<String, byte[]>> records) {
    Observation.createNotStarted("gridpulse.sink.batch", observations)
        .contextualName("write batch")
        .highCardinalityKeyValue("gridpulse.records", String.valueOf(records.size()))
        .observe(() -> decodeAndWrite(records));
  }

  private void decodeAndWrite(List<ConsumerRecord<String, byte[]>> records) {
    List<GridEvent> events = new ArrayList<>(records.size());
    for (int i = 0; i < records.size(); i++) {
      try {
        events.add(codec.decode(records.get(i).value()));
      } catch (RuntimeException e) {
        // The error handler commits offsets up to the failed record, so the records before it
        // must be written first. It then dead-letters record i and redelivers the rest.
        write(events);
        metrics.undecodable();
        throw new BatchListenerFailedException(
            "undecodable record",
            new UndecodableEventException("record is not a valid GridPulse event", e),
            i);
      }
    }
    write(events);
  }

  private void write(List<GridEvent> events) {
    writer.write(events);
    // After the commit, so a refresh always sees these rows and the metrics count only what's
    // stored.
    aggregates.record(events);
    metrics.written(events);
  }
}
