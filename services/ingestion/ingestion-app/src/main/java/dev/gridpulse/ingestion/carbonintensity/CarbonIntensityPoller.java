package dev.gridpulse.ingestion.carbonintensity;

import dev.gridpulse.events.GridEvent;
import dev.gridpulse.ingestion.publish.EventPublisher;
import dev.gridpulse.ingestion.publish.EventPublisher.PublishResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls national and regional carbon intensity on a schedule and publishes the events. */
@Component
public class CarbonIntensityPoller {

  private static final Logger log = LoggerFactory.getLogger(CarbonIntensityPoller.class);

  private final CarbonIntensityClient client;
  private final CarbonIntensityMapper mapper;
  private final EventPublisher publisher;
  private final CarbonIntensityProperties properties;
  private final Clock clock;

  public CarbonIntensityPoller(
      CarbonIntensityClient client,
      CarbonIntensityMapper mapper,
      EventPublisher publisher,
      CarbonIntensityProperties properties,
      Clock clock) {
    this.client = client;
    this.mapper = mapper;
    this.publisher = publisher;
    this.properties = properties;
    this.clock = clock;
  }

  /** Two minutes past each half-hour, when the previous period's actual has usually arrived. */
  @Scheduled(cron = "${gridpulse.sources.carbon-intensity.cron}", zone = "UTC")
  public void scheduledPoll() {
    try {
      poll();
    } catch (RuntimeException e) {
      // Nothing is lost: the next poll re-fetches the lookback window.
      log.error(
          "Carbon intensity poll failed; the next poll will re-fetch the last {}",
          properties.lookback(),
          e);
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  public void pollOnStartup() {
    if (properties.pollOnStartup()) {
      scheduledPoll();
    }
  }

  /** Fetches, maps and publishes; returns once Kafka has acknowledged every event. */
  public PublishResult poll() {
    Instant now = clock.instant();
    List<GridEvent> events = new ArrayList<>();
    events.addAll(mapper.national(client.national(now.minus(properties.lookback())), now));
    events.addAll(mapper.regional(client.regional(now), now));
    PublishResult result = publisher.publishAll(events);
    log.info(
        "Carbon intensity poll: {} events published, {} dead-lettered",
        result.published(),
        result.deadLettered());
    return result;
  }
}
