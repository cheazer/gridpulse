package dev.gridpulse.sink;

import dev.gridpulse.events.EventType;
import dev.gridpulse.events.GridEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * What the sink stores, as metrics:
 *
 * <ul>
 *   <li>{@code gridpulse.sink.events}: events by {@code event_type} and {@code outcome} ({@code
 *       written}, or {@code undecodable} for records sent to the dead-letter topic).
 *   <li>{@code gridpulse.sink.write.age}: time since events of each type were last written. This is
 *       the ingestion symptom to alert on: whether a source's API is down, the poller has stopped,
 *       Kafka is unreachable or the sink is stuck, the age keeps growing.
 * </ul>
 */
@Component
public class SinkMetrics {

  private final MeterRegistry meters;
  private final Map<EventType, AtomicLong> lastWrite = new ConcurrentHashMap<>();

  public SinkMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  /** Call after the transaction holding {@code events} has committed. */
  public void written(List<GridEvent> events) {
    long now = meters.config().clock().wallTime();
    for (GridEvent event : events) {
      EventType type = event.eventType();
      meters
          .counter("gridpulse.sink.events", "event_type", type.wireName(), "outcome", "written")
          .increment();
      lastWrite.computeIfAbsent(type, this::registerAge).set(now);
    }
  }

  public void undecodable() {
    // The type is unknown: the record couldn't be read.
    meters
        .counter("gridpulse.sink.events", "event_type", "unknown", "outcome", "undecodable")
        .increment();
  }

  /**
   * The gauge for one type, registered on its first write. A type the sink has never written has no
   * age to report; after a restart there's no reading until data flows again, which an alert on a
   * missing series catches.
   */
  private AtomicLong registerAge(EventType type) {
    AtomicLong last = new AtomicLong();
    TimeGauge.builder(
            "gridpulse.sink.write.age",
            last,
            TimeUnit.MILLISECONDS,
            l -> meters.config().clock().wallTime() - l.get())
        .tag("event_type", type.wireName())
        .description("Time since events of this type were last written to TimescaleDB")
        .register(meters);
    return last;
  }
}
