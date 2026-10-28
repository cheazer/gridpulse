package dev.gridpulse.ingestion.elexon;

import static dev.gridpulse.ingestion.elexon.ElexonFixtures.POLLED_AT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventType;
import dev.gridpulse.events.EventValidator;
import dev.gridpulse.ingestion.elexon.ElexonPoller.ElexonPollResult;
import dev.gridpulse.ingestion.publish.EventPublisher.PublishResult;
import dev.gridpulse.ingestion.testing.FixtureServer;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/** The ingestion app's Elexon poller against a real Redpanda broker and recorded responses. */
@SpringBootTest(
    properties = {
      // No start-up or scheduled polls during the test; it calls poll() itself.
      "gridpulse.sources.carbon-intensity.poll-on-startup=false",
      "gridpulse.sources.carbon-intensity.cron=-",
      "gridpulse.sources.elexon.poll-on-startup=false",
      "gridpulse.sources.elexon.cron=-"
    })
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElexonPollerIT {

  @Container @ServiceConnection
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.2.4");

  static final FixtureServer API = ElexonFixtures.server();

  @DynamicPropertySource
  static void sourceUrl(DynamicPropertyRegistry registry) {
    registry.add("gridpulse.sources.elexon.base-url", () -> API.baseUrl().toString());
    registry.add("gridpulse.sources.elexon.http.retry.max-retries", () -> "3");
    registry.add("gridpulse.sources.elexon.http.retry.initial-delay", () -> "PT0.01S");
    registry.add("gridpulse.sources.elexon.http.retry.max-delay", () -> "PT0.05S");
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

  @Autowired ElexonPoller poller;

  /** Expected events per topic from the recorded responses. */
  private static final Map<EventType, Integer> EXPECTED =
      Map.of(
          EventType.DEMAND,
          2 * 90,
          EventType.GENERATION_MIX,
          90,
          EventType.SYSTEM_PRICE,
          48 + 41,
          EventType.DEMAND_FORECAST,
          64 + 64);

  @Test
  @Order(1)
  void onePollPublishesEveryDatasetToItsTopic() {
    ElexonPollResult result = poller.poll();

    int total = EXPECTED.values().stream().mapToInt(Integer::intValue).sum();
    assertThat(result).isEqualTo(new ElexonPollResult(new PublishResult(total, 0), List.of()));

    var codec = new EventCodec();
    var validator = new EventValidator();
    EXPECTED.forEach(
        (type, expected) -> {
          Set<UUID> ids = new HashSet<>();
          consume(type.topic(), expected)
              .forEach(
                  json -> {
                    assertThat(validator.validate(codec.readTree(json))).isEmpty();
                    ids.add(codec.decode(json).eventId());
                  });
          assertThat(ids).as(type.topic()).hasSize(expected);
        });
  }

  @Test
  @Order(2)
  void anUnavailableDatasetDoesNotStopTheOthers() {
    API.failNext(503, 503, 503, 503); // demand outturn is fetched first: 1 attempt + 3 retries

    ElexonPollResult result = poller.poll();

    assertThat(result.unavailableDatasets()).containsExactly("demand outturn");
    assertThat(result.published().published()).isEqualTo(90 + 48 + 41 + 64 + 64);
  }

  private static List<byte[]> consume(String topic, int expected) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            REDPANDA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "elexon-it-" + topic,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest");
    List<byte[]> values = new ArrayList<>();
    try (var consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (values.size() < expected && System.nanoTime() < deadline) {
        consumer.poll(Duration.ofMillis(500)).forEach(record -> values.add(record.value()));
      }
    }
    return values;
  }
}
