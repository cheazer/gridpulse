package dev.gridpulse.ingestion;

import dev.gridpulse.events.EventCodec;
import dev.gridpulse.events.EventValidator;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class IngestionApplication {

  public static void main(String[] args) {
    SpringApplication.run(IngestionApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  EventCodec eventCodec() {
    return new EventCodec();
  }

  @Bean
  EventValidator eventValidator() {
    return new EventValidator();
  }
}
