package dev.gridpulse.sink;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.GridEvent;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

/**
 * Consumes every event topic in batches and writes each batch in one transaction. Offsets are
 * committed only after the listener returns, i.e. after the database commit (ADR-0006).
 */
@Component
public class EventConsumer {

  private final EventCodec codec;
  private final EventWriter writer;

  public EventConsumer(EventCodec codec, EventWriter writer) {
    this.codec = codec;
    this.writer = writer;
  }

  @KafkaListener(topics = "#{T(dev.gridpulse.events.EventType).allTopics()}")
  public void consume(List<ConsumerRecord<String, byte[]>> records) {
    List<GridEvent> events = new ArrayList<>(records.size());
    for (int i = 0; i < records.size(); i++) {
      try {
        events.add(codec.decode(records.get(i).value()));
      } catch (RuntimeException e) {
        // The error handler commits offsets up to the failed record, so the records before it
        // must be written first. It then dead-letters record i and redelivers the rest.
        writer.write(events);
        throw new BatchListenerFailedException(
            "undecodable record",
            new UndecodableEventException("record is not a valid GridPulse event", e),
            i);
      }
    }
    writer.write(events);
  }
}
