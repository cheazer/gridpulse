package dev.gridpulse.sink;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventType;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.Region;
import dev.gridpulse.events.SettlementPeriods;
import dev.gridpulse.events.Source;
import dev.gridpulse.events.payload.SystemPrice;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The sink against real Redpanda and TimescaleDB, with the real migrations from db/migrations
 * applied by Flyway exactly as the migrate job does.
 */
@SpringBootTest(
    properties = {
      "spring.flyway.locations=filesystem:${gridpulse.migrations}",
      "spring.flyway.schemas=grid",
      "spring.flyway.default-schema=grid"
    })
@Testcontainers
class SinkIT {

  @Container @ServiceConnection
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.2.4");

  @Container @ServiceConnection
  static final PostgreSQLContainer TIMESCALE =
      new PostgreSQLContainer(
          DockerImageName.parse("timescale/timescaledb:2.30.2-pg17")
              .asCompatibleSubstituteFor("postgres"));

  /** Rows each golden example becomes: generation mix stores one row per fuel code. */
  private static final Map<String, Integer> GOLDEN_ROWS =
      Map.of(
          "carbon_intensity", 2,
          "generation_mix", 10 + 20, // two examples: 10 fuel codes, and all 20 incl. net exports
          "demand", 1,
          "demand_forecast", 1,
          "system_price", 1,
          "weather", 1);

  private final EventCodec codec = new EventCodec();

  @Autowired KafkaTemplate<String, byte[]> kafka;
  @Autowired JdbcTemplate jdbc;

  @Test
  void writesEveryGoldenEventAndReDeliveryChangesNothing() throws Exception {
    List<byte[]> golden = goldenExamples();
    List<UUID> ids = golden.stream().map(json -> codec.decode(json).eventId()).toList();

    golden.forEach(this::send);
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertGoldenRows(ids));

    // At-least-once delivery means duplicates will happen: send everything again, then a
    // sentinel behind it. Once the sentinel is written, the duplicates have been processed too.
    golden.forEach(this::send);
    GridEvent sentinel = systemPrice(Instant.parse("2026-10-27T19:00:00Z"));
    send(codec.encode(sentinel));
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> rowExists("system_price", sentinel.eventId()));

    assertGoldenRows(ids);
    assertThat(
            jdbc.queryForObject(
                "SELECT actual_gco2_per_kwh FROM grid.carbon_intensity WHERE event_id = ?",
                Integer.class,
                UUID.fromString("02903352-dee2-504f-abc8-385f37cff117")))
        .isEqualTo(172);
  }

  @Test
  void undecodableRecordIsDeadLetteredAndConsumptionContinues() {
    String topic = EventType.SYSTEM_PRICE.topic();
    kafka.send(topic, "GB", "this is not an event".getBytes(UTF_8)).join();
    GridEvent after = systemPrice(Instant.parse("2026-10-27T20:00:00Z"));
    send(codec.encode(after));

    await().atMost(Duration.ofSeconds(60)).until(() -> rowExists("system_price", after.eventId()));

    List<ConsumerRecord<String, byte[]>> deadLetters = consume(topic + ".dlq", 1);
    assertThat(deadLetters).hasSize(1);
    assertThat(new String(deadLetters.getFirst().value(), UTF_8)).isEqualTo("this is not an event");
    // The DLQ record carries the failure, so someone inspecting it can see why it was rejected.
    assertThat(
            new String(
                deadLetters
                    .getFirst()
                    .headers()
                    .lastHeader("kafka_dlt-exception-stacktrace")
                    .value(),
                UTF_8))
        .contains(UndecodableEventException.class.getName());
  }

  private void assertGoldenRows(List<UUID> ids) {
    GOLDEN_ROWS.forEach(
        (table, expected) ->
            assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM grid." + table + " WHERE event_id = ANY (?)",
                        Integer.class,
                        (Object) ids.toArray(UUID[]::new)))
                .as(table)
                .isEqualTo(expected));
  }

  private boolean rowExists(String table, UUID eventId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM grid." + table + " WHERE event_id = ?", Integer.class, eventId);
    return count != null && count > 0;
  }

  private void send(byte[] json) {
    GridEvent event = codec.decode(json);
    kafka.send(event.eventType().topic(), event.partitionKey(), json).join();
  }

  private static GridEvent systemPrice(Instant validFrom) {
    return GridEvent.create(
        EventType.SYSTEM_PRICE,
        Source.ELEXON_INSIGHTS,
        "system-price",
        Region.GB,
        validFrom,
        validFrom.plus(SettlementPeriods.PERIOD_LENGTH),
        SettlementPeriods.of(validFrom),
        null,
        Instant.parse("2026-10-27T21:00:00Z"),
        new SystemPrice(new BigDecimal("85.10"), new BigDecimal("85.10"), null));
  }

  private static List<byte[]> goldenExamples() throws IOException, URISyntaxException {
    Path dir = Path.of(SinkIT.class.getResource("/gridpulse/examples/v1/valid").toURI());
    try (Stream<Path> files = Files.list(dir)) {
      List<byte[]> examples = new ArrayList<>();
      for (Path file : files.sorted().toList()) {
        examples.add(Files.readAllBytes(file));
      }
      return examples;
    }
  }

  private static List<ConsumerRecord<String, byte[]>> consume(String topic, int expected) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            REDPANDA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "sink-it-" + topic,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest");
    List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
    try (var consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (records.size() < expected && System.nanoTime() < deadline) {
        consumer.poll(Duration.ofMillis(500)).forEach(records::add);
      }
    }
    return records;
  }
}
