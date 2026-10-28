package dev.gridpulse.sink;

import dev.gridpulse.events.EventCodec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@SpringBootApplication
public class SinkApplication {

  public static void main(String[] args) {
    SpringApplication.run(SinkApplication.class, args);
  }

  @Bean
  EventCodec eventCodec() {
    return new EventCodec();
  }

  /**
   * How failures are handled (ADR-0006):
   *
   * <ul>
   *   <li>A record that can't be decoded will never succeed, so it goes straight to {@code
   *       <topic>.dlq} with the exception in its headers, and the rest of the batch carries on.
   *   <li>Anything else (a database outage, say) retries the batch with exponential backoff up to
   *       one minute apart, indefinitely. Consumption pauses rather than discarding data, and
   *       resumes by itself when the database is back.
   * </ul>
   */
  @Bean
  DefaultErrorHandler errorHandler(KafkaOperations<?, ?> kafka) {
    var deadLetter =
        new DeadLetterPublishingRecoverer(
            kafka, (record, error) -> new TopicPartition(record.topic() + ".dlq", -1));
    var backOff = new ExponentialBackOff(1_000, 2);
    backOff.setMaxInterval(60_000);
    var handler = new DefaultErrorHandler(deadLetter, backOff);
    handler.addNotRetryableExceptions(UndecodableEventException.class);
    return handler;
  }
}
