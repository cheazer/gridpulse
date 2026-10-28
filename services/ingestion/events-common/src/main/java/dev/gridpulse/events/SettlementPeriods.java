package dev.gridpulse.events;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Converts between UTC instants and GB settlement periods. Settlement days are UK local days, so a
 * day has 46 periods when the clocks go forward and 50 when they go back.
 */
public final class SettlementPeriods {

  public static final ZoneId UK = ZoneId.of("Europe/London");
  public static final Duration PERIOD_LENGTH = Duration.ofMinutes(30);

  private SettlementPeriods() {}

  /** The settlement period containing {@code instant}. */
  public static SettlementPeriod of(Instant instant) {
    LocalDate date = instant.atZone(UK).toLocalDate();
    // Instants measure real elapsed time, so the missing or extra hour on clock-change days is
    // counted. (Python's aware-datetime subtraction uses wall-clock time and gets this wrong.)
    Duration sinceStartOfDay = Duration.between(startOfDay(date), instant);
    return new SettlementPeriod(date, (int) sinceStartOfDay.dividedBy(PERIOD_LENGTH) + 1);
  }

  /** The UTC instant at which a settlement period starts. */
  public static Instant start(SettlementPeriod settlementPeriod) {
    return startOfDay(settlementPeriod.date())
        .plus(PERIOD_LENGTH.multipliedBy(settlementPeriod.period() - 1L));
  }

  /** Number of settlement periods in a settlement date: 46, 48 or 50. */
  public static int periodsIn(LocalDate date) {
    Duration dayLength = Duration.between(startOfDay(date), startOfDay(date.plusDays(1)));
    return (int) dayLength.dividedBy(PERIOD_LENGTH);
  }

  /** Whether {@code instant} is exactly the start of a settlement period. */
  public static boolean isAligned(Instant instant) {
    return start(of(instant)).equals(instant);
  }

  private static Instant startOfDay(LocalDate date) {
    return date.atStartOfDay(UK).toInstant();
  }
}
