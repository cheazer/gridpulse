package dev.gridpulse.events.payload;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Carbon intensity for one half-hour, in gCO2/kWh. Regional data is forecast-only, so either value
 * may be null, but not both.
 */
public record CarbonIntensity(
    Integer forecastGco2PerKwh, Integer actualGco2PerKwh, IntensityIndex index) implements Payload {

  public CarbonIntensity {
    if (forecastGco2PerKwh == null && actualGco2PerKwh == null) {
      throw new IllegalArgumentException("carbon intensity needs a forecast or an actual value");
    }
  }

  /** NESO's qualitative band for an intensity value. */
  public enum IntensityIndex {
    VERY_LOW("very low"),
    LOW("low"),
    MODERATE("moderate"),
    HIGH("high"),
    VERY_HIGH("very high");

    private final String wireName;

    IntensityIndex(String wireName) {
      this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
      return wireName;
    }

    public static IntensityIndex fromWireName(String wireName) {
      for (IntensityIndex index : values()) {
        if (index.wireName.equals(wireName)) {
          return index;
        }
      }
      throw new IllegalArgumentException("unknown intensity index: " + wireName);
    }
  }
}
