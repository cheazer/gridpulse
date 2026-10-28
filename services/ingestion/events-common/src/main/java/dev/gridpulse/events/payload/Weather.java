package dev.gridpulse.events.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Hourly weather at one location. Measurements are nullable because a source can omit any of them.
 *
 * <p>The explicit property names are needed because snake_case conversion doesn't insert an
 * underscore before digits ({@code temperature2mC} would become {@code temperature2m_c}).
 */
public record Weather(
    Location location,
    @JsonProperty("temperature_2m_c") Double temperature2mC,
    @JsonProperty("wind_speed_10m_ms") Double windSpeed10mMs,
    @JsonProperty("wind_speed_100m_ms") Double windSpeed100mMs,
    @JsonProperty("shortwave_radiation_wm2") Double shortwaveRadiationWm2,
    @JsonProperty("cloud_cover_pct") Double cloudCoverPct)
    implements Payload {

  public Weather {
    Objects.requireNonNull(location, "location");
  }

  /**
   * @param id the location slug, also used as the event's {@code series}
   */
  public record Location(String id, double latitude, double longitude) {
    public Location {
      Objects.requireNonNull(id, "id");
    }
  }
}
