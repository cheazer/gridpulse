package dev.gridpulse.ingestion.openmeteo;

import dev.gridpulse.events.GridEvent;
import dev.gridpulse.ingestion.publish.EventPublisher;
import dev.gridpulse.ingestion.publish.EventPublisher.PublishResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls weather forecasts hourly and reanalysis daily for the configured locations (ADR-0008). */
@Component
public class OpenMeteoPoller {

  private static final Logger log = LoggerFactory.getLogger(OpenMeteoPoller.class);

  private final OpenMeteoClient client;
  private final OpenMeteoMapper mapper;
  private final EventPublisher publisher;
  private final OpenMeteoProperties properties;
  private final Clock clock;

  public OpenMeteoPoller(
      OpenMeteoClient client,
      OpenMeteoMapper mapper,
      EventPublisher publisher,
      OpenMeteoProperties properties,
      Clock clock) {
    this.client = client;
    this.mapper = mapper;
    this.publisher = publisher;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(cron = "${gridpulse.sources.open-meteo.forecast-cron}", zone = "UTC")
  public void scheduledForecastPoll() {
    run("forecast", this::pollForecasts);
  }

  @Scheduled(cron = "${gridpulse.sources.open-meteo.reanalysis-cron}", zone = "UTC")
  public void scheduledReanalysisPoll() {
    run("reanalysis", this::pollReanalysis);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void pollOnStartup() {
    if (properties.pollOnStartup()) {
      scheduledForecastPoll();
      scheduledReanalysisPoll();
    }
  }

  public PublishResult pollForecasts() {
    Instant now = clock.instant();
    var responses = client.forecast(properties.locations(), properties.forecastDays());
    return publish("forecast", mapper.forecasts(properties.locations(), responses, now));
  }

  /**
   * The last {@code reanalysisDays} complete UTC days; the archive lags real time by about a day.
   */
  public PublishResult pollReanalysis() {
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    var responses =
        client.reanalysis(
            properties.locations(),
            today.minusDays(properties.reanalysisDays()),
            today.minusDays(1));
    return publish("reanalysis", mapper.reanalysis(properties.locations(), responses, now));
  }

  private PublishResult publish(String dataset, List<GridEvent> events) {
    PublishResult result = publisher.publishAll(events);
    log.info(
        "Open-Meteo {} poll: {} events published, {} dead-lettered",
        dataset,
        result.published(),
        result.deadLettered());
    return result;
  }

  private static void run(String dataset, Runnable poll) {
    try {
      poll.run();
    } catch (RuntimeException e) {
      log.error("Open-Meteo {} poll failed; the next poll re-fetches the same window", dataset, e);
    }
  }
}
