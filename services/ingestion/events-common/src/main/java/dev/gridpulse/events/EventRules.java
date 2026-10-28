package dev.gridpulse.events;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Data rules JSON Schema can't express. Mirrors {@code schemas/tools/validate_examples.py}; the
 * golden-example tests keep the two in step.
 */
public final class EventRules {

  /** The rule names match {@code expect_check} in the invalid golden examples. */
  public enum Check {
    EVENT_ID,
    INTERVAL,
    SETTLEMENT
  }

  private EventRules() {}

  /** Returns every rule the event breaks, with a message; empty if it breaks none. */
  public static Map<Check, String> violations(GridEvent event) {
    Map<Check, String> violations = new EnumMap<>(Check.class);

    UUID expectedId =
        EventIds.derive(
            event.source(),
            event.eventType(),
            event.series(),
            event.region(),
            event.validFrom(),
            event.issuedAt());
    if (!expectedId.equals(event.eventId())) {
      violations.put(Check.EVENT_ID, "expected " + expectedId + ", got " + event.eventId());
    }

    if (!event.validTo().isAfter(event.validFrom())) {
      violations.put(Check.INTERVAL, "valid_to must be after valid_from");
    }

    if ((event.settlementDate() == null) != (event.settlementPeriod() == null)) {
      violations.put(
          Check.SETTLEMENT,
          "settlement_date and settlement_period must both be set or both be null");
    } else if (event.settlementPeriod() != null) {
      SettlementPeriod expected = SettlementPeriods.of(event.validFrom());
      if (!expected.date().equals(event.settlementDate())
          || expected.period() != event.settlementPeriod()) {
        violations.put(
            Check.SETTLEMENT,
            "expected %s SP%d, got %s SP%d"
                .formatted(
                    expected.date(),
                    expected.period(),
                    event.settlementDate(),
                    event.settlementPeriod()));
      } else if (!violations.containsKey(Check.INTERVAL)
          && !Duration.between(event.validFrom(), event.validTo())
              .equals(SettlementPeriods.PERIOD_LENGTH)) {
        violations.put(Check.SETTLEMENT, "settlement-period events must span exactly 30 minutes");
      }
    }

    return violations;
  }
}
