package dev.gridpulse.ingestion.carbonintensity;

import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.NationalPeriod;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.NationalResponse;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.RegionalPeriod;
import dev.gridpulse.ingestion.carbonintensity.CarbonIntensityApi.RegionalResponse;
import dev.gridpulse.ingestion.http.HttpRetries;
import dev.gridpulse.ingestion.http.SourceUnavailableException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads 48 hours (96 half-hours) of national and regional carbon intensity per call. */
@Component
public class CarbonIntensityClient {

  private static final DateTimeFormatter API_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC);

  private final RestClient http;
  private final RetryTemplate retry;

  public CarbonIntensityClient(RestClient.Builder builder, CarbonIntensityProperties properties) {
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
    requestFactory.setReadTimeout(properties.readTimeout());
    this.http =
        builder
            .baseUrl(properties.baseUrl().toString())
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
    var retrySettings = properties.retry();
    this.retry =
        HttpRetries.template(
            retrySettings.maxRetries(), retrySettings.initialDelay(), retrySettings.maxDelay());
  }

  /** 48 hours from the period containing {@code from}, with actuals where known. */
  public List<NationalPeriod> national(Instant from) {
    return get("/intensity/%s/fw48h", from, NationalResponse.class).data();
  }

  /** Regional forecasts for 48 hours from the period containing {@code from}. */
  public List<RegionalPeriod> regional(Instant from) {
    return get("/regional/intensity/%s/fw48h", from, RegionalResponse.class).data();
  }

  private <T> T get(String pathFormat, Instant from, Class<T> type) {
    // Formatted into the path rather than passed as a URI template variable: Spring encodes
    // template variables strictly, turning ':' into %3A, which this API rejects with a 400.
    String path = pathFormat.formatted(API_TIME.format(from));
    try {
      return retry.execute(() -> http.get().uri(path).retrieve().body(type));
    } catch (RetryException e) {
      throw new SourceUnavailableException("Carbon Intensity API " + path, e.getCause());
    }
  }
}
