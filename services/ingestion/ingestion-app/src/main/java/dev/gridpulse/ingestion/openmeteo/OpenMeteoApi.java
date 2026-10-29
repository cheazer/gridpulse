package dev.gridpulse.ingestion.openmeteo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Response shapes of the Open-Meteo forecast and archive APIs (identical for both). */
final class OpenMeteoApi {

  private OpenMeteoApi() {}

  /** One location's hourly series; every list is aligned with {@code time}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record LocationResponse(Hourly hourly) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Hourly(
      List<Long> time, // Unix epoch seconds (timeformat=unixtime)
      @JsonProperty("temperature_2m") List<Double> temperature2m,
      @JsonProperty("wind_speed_10m") List<Double> windSpeed10m,
      @JsonProperty("wind_speed_100m") List<Double> windSpeed100m,
      @JsonProperty("shortwave_radiation") List<Double> shortwaveRadiation,
      @JsonProperty("cloud_cover") List<Double> cloudCover) {}
}
