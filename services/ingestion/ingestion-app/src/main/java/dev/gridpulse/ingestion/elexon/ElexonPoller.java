package dev.gridpulse.ingestion.elexon;

import dev.gridpulse.events.GridEvent;
import dev.gridpulse.events.SettlementPeriods;
import dev.gridpulse.ingestion.elexon.ElexonClient.DayAheadProduct;
import dev.gridpulse.ingestion.publish.EventPublisher;
import dev.gridpulse.ingestion.publish.EventPublisher.PublishResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls demand outturn, generation by fuel, system prices and NESO's day-ahead forecasts. Datasets
 * are fetched independently: one unavailable endpoint doesn't stop the others being published.
 */
@Component
public class ElexonPoller {

  private static final Logger log = LoggerFactory.getLogger(ElexonPoller.class);

  private final ElexonClient client;
  private final ElexonMapper mapper;
  private final EventPublisher publisher;
  private final ElexonProperties properties;
  private final Clock clock;

  public ElexonPoller(
      ElexonClient client,
      ElexonMapper mapper,
      EventPublisher publisher,
      ElexonProperties properties,
      Clock clock) {
    this.client = client;
    this.mapper = mapper;
    this.publisher = publisher;
    this.properties = properties;
    this.clock = clock;
  }

  /** Poll result, including any datasets that couldn't be fetched this time. */
  public record ElexonPollResult(PublishResult published, List<String> unavailableDatasets) {}

  @Scheduled(cron = "${gridpulse.sources.elexon.cron}", zone = "UTC")
  public void scheduledPoll() {
    try {
      poll();
    } catch (RuntimeException e) {
      log.error("Elexon poll failed; the next poll re-fetches the same settlement days", e);
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  public void pollOnStartup() {
    if (properties.pollOnStartup()) {
      scheduledPoll();
    }
  }

  public ElexonPollResult poll() {
    Instant now = clock.instant();
    LocalDate today = SettlementPeriods.of(now).date(); // a UK settlement date, not a UTC one
    LocalDate from = today.minusDays(properties.lookbackDays());

    List<GridEvent> events = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    collect(
        "demand outturn",
        () -> mapper.demandOutturn(client.demandOutturn(from, today), now),
        events,
        unavailable);
    collect(
        "FUELHH",
        () -> mapper.fuelMix(client.fuelGeneration(from, today), now),
        events,
        unavailable);
    for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
      LocalDate settlementDate = day;
      collect(
          "system prices " + settlementDate,
          () -> mapper.systemPrices(client.systemPrices(settlementDate), now),
          events,
          unavailable);
    }
    for (DayAheadProduct product : DayAheadProduct.values()) {
      collect(
          product.name(),
          () -> mapper.dayAheadForecast(client.latestDayAheadForecast(product), product, now),
          events,
          unavailable);
    }

    PublishResult result = publisher.publishAll(events);
    log.info(
        "Elexon poll: {} events published, {} dead-lettered{}",
        result.published(),
        result.deadLettered(),
        unavailable.isEmpty() ? "" : "; unavailable: " + unavailable);
    return new ElexonPollResult(result, List.copyOf(unavailable));
  }

  private static void collect(
      String dataset,
      Supplier<List<GridEvent>> fetch,
      List<GridEvent> events,
      List<String> unavailable) {
    try {
      events.addAll(fetch.get());
    } catch (RuntimeException e) {
      unavailable.add(dataset);
      log.warn("Elexon {} unavailable this poll; publishing the other datasets", dataset, e);
    }
  }
}
