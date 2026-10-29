package dev.gridpulse.ingestion.openmeteo;

import static dev.gridpulse.ingestion.openmeteo.OpenMeteoFixtures.POLLED_AT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventType;
import dev.gridpulse.events.EventValidator;
import dev.gridpulse.events.GridEvent;
import dev.gridpulse.ingestion.publish.EventPublisher.PublishResult;
import dev.gridpulse.ingestion.testing.FixtureServer;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The Open-Meteo poller against a real Redpanda broker and recorded responses, with the 8 locations
 * from application.yaml.
 */
@SpringBootTest
@ActiveProfiles("it") // no start-up or scheduled polls; tests call poll() themselves
@Testcontainers
class OpenMeteoPollerIT {

  @Container @ServiceConnection
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.2.4");

  static final FixtureServer API = OpenMeteoFixtures.server();

  @DynamicPropertySource
  static void sourceUrls(DynamicPropertyRegistry registry) {
    registry.add("gridpulse.sources.open-meteo.forecast-base-url", () -> API.baseUrl().toString());
    registry.add("gridpulse.sources.open-meteo.archive-base-url", () -> API.baseUrl().toString());
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

  @Autowired OpenMeteoPoller poller;
  @Autowired OpenMeteoProperties properties;

  @Test
  void configuredLocationsMatchTheRecordedOnes() {
    // The fixture server only answers for exactly these coordinates.
    assertThat(properties.locations()).isEqualTo(OpenMeteoFixtures.LOCATIONS);
  }

  @Test
  void forecastAndReanalysisPollsPublishValidWeatherEvents() {
    assertThat(poller.pollForecasts()).isEqualTo(new PublishResult(392, 0));
    assertThat(poller.pollReanalysis()).isEqualTo(new PublishResult(1920, 0));

    var codec = new EventCodec();
    var validator = new EventValidator();
    List<GridEvent> events = new ArrayList<>();
    for (byte[] json : consume(EventType.WEATHER.topic(), 392 + 1920)) {
      assertThat(validator.validate(codec.readTree(json))).isEmpty();
      events.add(codec.decode(json));
    }
    assertThat(events.stream().map(GridEvent::eventId).distinct()).hasSize(392 + 1920);
    assertThat(events.stream().filter(GridEvent::isForecast)).hasSize(392);
  }

  private static List<byte[]> consume(String topic, int expected) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "open-meteo-it",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
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
