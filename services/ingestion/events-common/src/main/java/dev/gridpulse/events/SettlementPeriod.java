package dev.gridpulse.events;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A GB settlement period: a 1-based half-hour within a UK local settlement date.
 *
 * @param period 1–48 on a normal day, up to 46 or 50 on clock-change days
 */
public record SettlementPeriod(LocalDate date, int period) {

  public SettlementPeriod {
    Objects.requireNonNull(date, "date");
    int max = SettlementPeriods.periodsIn(date);
    if (period < 1 || period > max) {
      throw new IllegalArgumentException(date + " has periods 1–" + max + ", not " + period);
    }
  }
}
