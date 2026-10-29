package dev.gridpulse.sink;

import dev.gridpulse.events.EventType;
import dev.gridpulse.events.GridEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes continuous aggregates for writes older than their refresh policy's window (ADR-0009).
 *
 * <p>Real-time aggregation reads raw rows only above the materialisation watermark, and the policy
 * only re-materialises the last 30 days. Without this, backfilled or very late data would never
 * appear in the daily views.
 */
@Component
public class AggregateRefresher {

  private static final Logger log = LoggerFactory.getLogger(AggregateRefresher.class);

  /** Event types feeding a daily aggregate, and which aggregate. Mirrors db/migrations/V3. */
  private static final Map<EventType, String> AGGREGATES =
      Map.of(
          EventType.CARBON_INTENSITY, "grid.carbon_intensity_daily",
          EventType.DEMAND, "grid.demand_daily",
          EventType.GENERATION_MIX, "grid.generation_mix_daily");

  private record Range(Instant from, Instant to) {
    Range union(Range other) {
      return new Range(
          from.isBefore(other.from) ? from : other.from, to.isAfter(other.to) ? to : other.to);
    }
  }

  private final Map<String, Range> pending = new ConcurrentHashMap<>();
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration policyWindow;

  public AggregateRefresher(
      JdbcTemplate jdbc,
      Clock clock,
      @Value("${gridpulse.sink.aggregates.policy-window}") Duration policyWindow) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.policyWindow = policyWindow;
  }

  /** Notes committed events whose time is older than the refresh policy reaches. */
  public void record(List<GridEvent> events) {
    // A day's margin inside the policy window, so nothing falls between the two.
    Instant cutoff = clock.instant().minus(policyWindow).plus(Duration.ofDays(1));
    for (GridEvent event : events) {
      String aggregate = AGGREGATES.get(event.eventType());
      if (aggregate != null && event.validFrom().isBefore(cutoff)) {
        pending.merge(aggregate, new Range(event.validFrom(), event.validFrom()), Range::union);
      }
    }
  }

  @Scheduled(fixedDelayString = "${gridpulse.sink.aggregates.refresh-interval}")
  public void refreshPending() {
    for (String aggregate : List.copyOf(pending.keySet())) {
      Range range = pending.remove(aggregate);
      if (range == null) {
        continue;
      }
      // Buckets are London days; a day either side covers any offset, and the refresh only
      // re-materialises buckets that lie wholly inside the window.
      OffsetDateTime from =
          range
              .from()
              .truncatedTo(ChronoUnit.DAYS)
              .minus(Duration.ofDays(1))
              .atOffset(ZoneOffset.UTC);
      OffsetDateTime to =
          range.to().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC);
      try {
        // Not in a transaction: TimescaleDB refuses to refresh inside one.
        jdbc.update(
            "CALL refresh_continuous_aggregate(?::regclass, ?::timestamptz, ?::timestamptz)",
            aggregate,
            from,
            to);
        log.info("Refreshed {} for {} to {}", aggregate, from.toLocalDate(), to.toLocalDate());
      } catch (RuntimeException e) {
        pending.merge(aggregate, range, Range::union); // try again next time
        log.warn("Refreshing {} failed; will retry", aggregate, e);
      }
    }
  }
}
