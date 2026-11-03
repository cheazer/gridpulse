package dev.gridpulse.sink;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.GridEvent;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The sink's metrics in a registry with a clock the test moves. */
class SinkMetricsTest {

  private final MockClock clock = new MockClock();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
  private final SinkMetrics metrics = new SinkMetrics(meters);

  @Test
  void countsWrittenEventsByType() {
    GridEvent carbon = example("carbon-intensity-national-actual.json");

    metrics.written(List.of(carbon, carbon, example("system-price.json")));

    assertThat(count("carbon_intensity", "written")).isEqualTo(2);
    assertThat(count("system_price", "written")).isEqualTo(1);
  }

  @Test
  void writeAgeGrowsUntilTheNextWriteOfThatType() {
    GridEvent carbon = example("carbon-intensity-national-actual.json");
    metrics.written(List.of(carbon));

    clock.add(Duration.ofMinutes(45));
    assertThat(age("carbon_intensity")).isEqualTo(45 * 60);

    metrics.written(List.of(carbon));
    assertThat(age("carbon_intensity")).isZero();
    assertThat(
            meters.find("gridpulse.sink.write.age").tag("event_type", "system_price").timeGauge())
        .as("no age for a type never written")
        .isNull();
  }

  @Test
  void undecodableRecordsAreCountedWithoutAType() {
    metrics.undecodable();

    assertThat(count("unknown", "undecodable")).isEqualTo(1);
  }

  private double count(String eventType, String outcome) {
    return meters
        .get("gridpulse.sink.events")
        .tag("event_type", eventType)
        .tag("outcome", outcome)
        .counter()
        .count();
  }

  private double age(String eventType) {
    return meters
        .get("gridpulse.sink.write.age")
        .tag("event_type", eventType)
        .timeGauge()
        .value(TimeUnit.SECONDS);
  }

  private static GridEvent example(String name) {
    try (InputStream in =
        SinkMetricsTest.class.getResourceAsStream("/gridpulse/examples/v1/valid/" + name)) {
      return new EventCodec().decode(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
