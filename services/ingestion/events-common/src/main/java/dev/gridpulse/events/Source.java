package dev.gridpulse.events;

import com.fasterxml.jackson.annotation.JsonValue;

/** Upstream data providers. */
public enum Source {
  NESO_CARBON_INTENSITY("neso-carbon-intensity"),
  ELEXON_INSIGHTS("elexon-insights"),
  NESO_DATA_PORTAL("neso-data-portal"),
  OPEN_METEO("open-meteo");

  private final String wireName;

  Source(String wireName) {
    this.wireName = wireName;
  }

  @JsonValue
  public String wireName() {
    return wireName;
  }
}
