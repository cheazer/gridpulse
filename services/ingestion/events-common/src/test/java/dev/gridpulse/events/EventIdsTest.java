package dev.gridpulse.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventIdsTest {

  @Test
  void uuidV5MatchesTheRfcTestVector() {
    // RFC 9562 Appendix A.4: DNS namespace, name "www.example.com"
    UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    assertThat(EventIds.uuidV5(dns, "www.example.com"))
        .isEqualTo(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"));
  }

  @Test
  void derivesTheGoldenExampleId() {
    UUID id =
        EventIds.derive(
            Source.NESO_CARBON_INTENSITY,
            EventType.CARBON_INTENSITY,
            "intensity",
            Region.GB,
            Instant.parse("2026-10-27T18:00:00Z"),
            null);
    assertThat(id).isEqualTo(UUID.fromString("02903352-dee2-504f-abc8-385f37cff117"));
    assertThat(id.version()).isEqualTo(5);
  }

  @Test
  void eachForecastRevisionGetsItsOwnId() {
    Instant validFrom = Instant.parse("2026-10-29T17:00:00Z");
    UUID morning =
        EventIds.derive(
            Source.ELEXON_INSIGHTS,
            EventType.DEMAND_FORECAST,
            "ndf",
            Region.GB,
            validFrom,
            Instant.parse("2026-10-28T09:00:00Z"));
    UUID afternoon =
        EventIds.derive(
            Source.ELEXON_INSIGHTS,
            EventType.DEMAND_FORECAST,
            "ndf",
            Region.GB,
            validFrom,
            Instant.parse("2026-10-28T15:00:00Z"));
    assertThat(morning).isNotEqualTo(afternoon);
  }

  @Test
  void subSecondTimestampsAreRejectedRatherThanTruncated() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> UtcTimestamps.canonical(Instant.parse("2026-10-27T18:00:00.500Z")));
    assertThat(UtcTimestamps.canonical(Instant.parse("2026-10-27T18:00:00Z")))
        .isEqualTo("2026-10-27T18:00:00Z");
  }
}
