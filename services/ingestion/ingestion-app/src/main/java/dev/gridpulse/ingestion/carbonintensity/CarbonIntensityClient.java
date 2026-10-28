package dev.gridpulse.ingestion.carbonintensity;

import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.NationalPeriod;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.NationalResponse;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.RegionalPeriod;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.RegionalResponse;
import dev.gridpulse.ingestion.http.HttpSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads 48 hours (96 half-hours) of national and regional carbon intensity per call. */
@Component
public class CarbonIntensityClient {

  private static final DateTimeFormatter API_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC);

  private final HttpSource http;

  public CarbonIntensityClient(RestClient.Builder builder, CarbonIntensityProperties properties) {
    this.http =
        new HttpSource("Carbon Intensity API", builder, properties.baseUrl(), properties.http());
  }

  /** 48 hours from the period containing {@code from}, with actuals where known. */
  public List<NationalPeriod> national(Instant from) {
    return http.get(path("/intensity/%s/fw48h", from), NationalResponse.class).data();
  }

  /** Regional forecasts for 48 hours from the period containing {@code from}. */
  public List<RegionalPeriod> regional(Instant from) {
    return http.get(path("/regional/intensity/%s/fw48h", from), RegionalResponse.class).data();
  }

  // Formatted into the path rather than passed as a URI template variable: Spring encodes
  // template variables strictly, turning ':' into %3A, which this API rejects with a 400.
  private static String path(String format, Instant from) {
    return format.formatted(API_TIME.format(from));
  }
}
