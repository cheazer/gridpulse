package dev.gridpulse.ingestion.carbonintensity;

import static dev.gridpulse.ingestion.carbonintensity.Fixtures.POLLED_AT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventType;
import dev.gridpulse.events.EventValidator;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.ingestion.publish.EventPublisher;
import dev.gridpulse.ingestion.publish.EventPublisher.PublishResult;
import dev.gridpulse.ingestion.testing.FixtureServer;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * The whole ingestion app against a real Redpanda broker and the recorded API responses: one poll
 * must put every event, schema-valid and keyed by region, on the carbon intensity topic.
 */
@SpringBootTest
@ActiveProfiles("it") // no start-up or scheduled polls; tests call poll() themselves
@Testcontainers
class CarbonIntensityPollerIT {

  @Container @ServiceConnection
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.2.4");

  static final FixtureServer API = Fixtures.server();

  @DynamicPropertySource
  static void sourceUrl(DynamicPropertyRegistry registry) {
    registry.add("gridpulse.sources.carbon-intensity.base-url", () -> API.baseUrl().toString());
  }

  @TestConfiguration
  static class FixedClock {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(POLLED_AT, ZoneOffset.UTC);
    }
  }

  @AfterAll
  static void stopApi() {
    API.close();
  }

  @Autowired CarbonIntensityPoller poller;

  @Value("${spring.main.keep-alive:false}")
  boolean keepAlive;

  @Test
  void keepsTheJvmAliveBetweenPolls() {
    // Regression: virtual threads are daemon threads, so without keep-alive the app exited after
    // start-up and the container restart policy turned that into a poll every few seconds.
    assertThat(keepAlive).isTrue();
  }

  @Test
  void onePollPublishesEveryEventValidAndKeyedByRegion() {
    PublishResult result = poller.poll();

    assertThat(result).isEqualTo(new PublishResult(95 + 1615, 0));

    var codec = new EventCodec();
    var validator = new EventValidator();
    List<ConsumerRecord<String, byte[]>> records =
        consumeAll(EventType.CARBON_INTENSITY.topic(), result.published());
    assertThat(records).hasSize(result.published());
    assertThat(records)
        .allSatisfy(
            record -> {
              GridEvent event = codec.decode(record.value());
              assertThat(validator.validate(codec.readTree(record.value()))).isEmpty();
              assertThat(record.key()).isEqualTo(event.region().slug());
              assertThat(
                      new String(
                          record.headers().lastHeader(EventPublisher.EVENT_TYPE_HEADER).value(),
                          UTF_8))
                  .isEqualTo("carbon_intensity");
            });
  }

  private static List<ConsumerRecord<String, byte[]>> consumeAll(String topic, int expected) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "poller-it",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
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
