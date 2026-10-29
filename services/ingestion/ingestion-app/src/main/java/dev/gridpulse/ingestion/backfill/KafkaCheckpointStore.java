package dev.gridpulse.ingestion.backfill;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Checkpoints in a compacted Kafka topic keyed {@code job|chunkStart} (ADR-0009). The ingestion app
 * has no database by design; compaction keeps one record per chunk however often the backfill runs.
 */
@Component
public class KafkaCheckpointStore implements CheckpointStore {

  private final KafkaTemplate<String, byte[]> kafka;
  private final String topic;

  public KafkaCheckpointStore(KafkaTemplate<String, byte[]> kafka, BackfillProperties properties) {
    this.kafka = kafka;
    this.topic = properties.checkpointTopic();
  }

  /**
   * Reads the topic from the beginning to its current end; a missing topic means nothing is done.
   */
  @Override
  public Set<String> completed() {
    Map<String, Object> config = new HashMap<>();
    // The producer's broker address, which is the one in use (it may come from a service
    // connection rather than spring.kafka.* properties).
    config.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
    Set<String> done = new HashSet<>();
    try (var consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      var metadata = consumer.partitionsFor(topic, Duration.ofSeconds(30));
      if (metadata == null || metadata.isEmpty()) {
        return done; // topic not created yet: nothing has been checkpointed
      }
      List<TopicPartition> partitions =
          metadata.stream().map(info -> new TopicPartition(topic, info.partition())).toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
        consumer.poll(Duration.ofMillis(500)).forEach(record -> done.add(record.key()));
      }
    }
    return done;
  }

  @Override
  public void markCompleted(String job, LocalDate chunkStart, LocalDate chunkEnd, int events) {
    String value =
        "{\"job\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"events\":%d,\"completed_at\":\"%s\"}"
            .formatted(job, chunkStart, chunkEnd, events, Instant.now());
    kafka.send(topic, CheckpointStore.key(job, chunkStart), value.getBytes(UTF_8)).join();
  }
}
