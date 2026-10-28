package dev.gridpulse.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SettlementPeriodsTest {

  @ParameterizedTest(name = "{0} has {1} periods")
  @CsvSource({
    "2026-10-27, 48", // ordinary GMT day
    "2026-07-01, 48", // ordinary BST day
    "2026-03-29, 46", // clocks go forward: 23-hour day
    "2026-10-25, 50" // clocks go back: 25-hour day
  })
  void countsPeriodsPerDay(LocalDate date, int expected) {
    assertThat(SettlementPeriods.periodsIn(date)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} -> {1} SP{2}")
  @CsvSource({
    "2026-10-27T18:00:00Z, 2026-10-27, 37", // GMT: local = UTC
    "2026-10-24T12:00:00Z, 2026-10-24, 27", // BST: 13:00 local, not SP25
    "2026-10-24T23:00:00Z, 2026-10-25,  1", // 00:00 BST on clock-change day = 23:00Z on the 24th
    "2026-10-25T00:30:00Z, 2026-10-25,  4", // 01:30 BST
    "2026-10-25T01:00:00Z, 2026-10-25,  5", // first 01:00 GMT after the clocks go back
    "2026-10-25T23:30:00Z, 2026-10-25, 50", // last period of the 25-hour day
    "2026-03-29T01:00:00Z, 2026-03-29,  3", // 02:00 BST: 01:00-02:00 local never happened
    "2026-03-29T22:30:00Z, 2026-03-29, 46" // last period of the 23-hour day
  })
  void mapsInstantsToPeriods(Instant instant, LocalDate date, int period) {
    assertThat(SettlementPeriods.of(instant)).isEqualTo(new SettlementPeriod(date, period));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"2026-10-27", "2026-03-29", "2026-10-25"})
  void startAndOfAreInversesForEveryPeriodOfTheDay(LocalDate date) {
    for (int p = 1; p <= SettlementPeriods.periodsIn(date); p++) {
      SettlementPeriod period = new SettlementPeriod(date, p);
      Instant start = SettlementPeriods.start(period);
      assertThat(SettlementPeriods.of(start)).isEqualTo(period);
      assertThat(SettlementPeriods.isAligned(start)).isTrue();
    }
  }

  @Test
  void periodBeyondTheDayIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SettlementPeriod(LocalDate.parse("2026-10-27"), 49));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SettlementPeriod(LocalDate.parse("2026-03-29"), 47));
    assertThat(new SettlementPeriod(LocalDate.parse("2026-10-25"), 50).period()).isEqualTo(50);
  }

  @Test
  void midPeriodInstantIsNotAligned() {
    assertThat(SettlementPeriods.isAligned(Instant.parse("2026-10-27T18:10:00Z"))).isFalse();
  }
}
