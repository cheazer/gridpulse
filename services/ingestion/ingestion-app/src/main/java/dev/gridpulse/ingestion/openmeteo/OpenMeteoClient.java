package dev.gridpulse.ingestion.openmeteo;

import dev.gridpulse.ingestion.http.HttpSource;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoApi.LocationResponse;
import dev.gridpulse.ingestion.openmeteo.OpenMeteoProperties.Location;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hourly weather for all configured locations in one request per call (ADR-0008). Results are in
 * the same order as the locations passed in.
 */
@Component
public class OpenMeteoClient {

  static final String VARIABLES =
      "temperature_2m,wind_speed_10m,wind_speed_100m,shortwave_radiation,cloud_cover";
  static final String COMMON =
      "&hourly=" + VARIABLES + "&wind_speed_unit=ms&timeformat=unixtime&timezone=GMT";

  private final HttpSource forecastApi;
  private final HttpSource archiveApi;
  private final JsonMapper json;

  public OpenMeteoClient(
      RestClient.Builder builder, OpenMeteoProperties properties, JsonMapper json) {
    this.forecastApi =
        new HttpSource(
            "Open-Meteo forecast API", builder, properties.forecastBaseUrl(), properties.http());
    this.archiveApi =
        new HttpSource(
            "Open-Meteo archive API", builder, properties.archiveBaseUrl(), properties.http());
    this.json = json;
  }

  /** Forecasts from today 00:00 UTC for {@code days} days. */
  public List<LocationResponse> forecast(List<Location> locations, int days) {
    String query = coordinates(locations) + COMMON + "&forecast_days=" + days;
    return read(forecastApi.get("/forecast" + query, JsonNode.class), locations);
  }

  /** Reanalysis for whole UTC days, {@code start} to {@code end} inclusive. */
  public List<LocationResponse> reanalysis(
      List<Location> locations, LocalDate start, LocalDate end) {
    String query = coordinates(locations) + COMMON + "&start_date=" + start + "&end_date=" + end;
    return read(archiveApi.get("/archive" + query, JsonNode.class), locations);
  }

  static String coordinates(List<Location> locations) {
    return "?latitude="
        + join(locations, Location::latitude)
        + "&longitude="
        + join(locations, Location::longitude);
  }

  private static String join(List<Location> locations, Function<Location, Double> coordinate) {
    return locations.stream().map(coordinate).map(String::valueOf).collect(Collectors.joining(","));
  }

  /** Several locations come back as an array, a single one as a bare object. */
  private List<LocationResponse> read(JsonNode body, List<Location> locations) {
    List<LocationResponse> responses =
        body.isArray()
            ? body.valueStream().map(n -> json.treeToValue(n, LocationResponse.class)).toList()
            : List.of(json.treeToValue(body, LocationResponse.class));
    if (responses.size() != locations.size()) {
      throw new IllegalStateException(
          "asked for " + locations.size() + " locations, got " + responses.size());
    }
    return responses;
  }
}
