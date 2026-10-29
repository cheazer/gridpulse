package dev.gridpulse.ingestion.http;

import java.net.URI;
import java.net.http.HttpClient;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP access to one upstream API with its timeouts, retries and rate limit in one place, so each
 * source client only describes its endpoints.
 */
public final class HttpSource {

  private final String name;
  private final RestClient http;
  private final RetryTemplate retry;
  private final RateLimiter rateLimiter;

  public HttpSource(String name, RestClient.Builder builder, URI baseUrl, HttpSettings settings) {
    this.name = name;
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                // Follow redirects (NESO downloads go to object storage); never https->http.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    requestFactory.setReadTimeout(settings.readTimeout());
    this.http =
        builder
            .clone()
            .baseUrl(baseUrl.toString())
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
    var retrySettings = settings.retry();
    this.retry =
        HttpRetries.template(
            retrySettings.maxRetries(), retrySettings.initialDelay(), retrySettings.maxDelay());
    this.rateLimiter = new RateLimiter(settings.requestsPerSecond(), settings.burst());
  }

  /**
   * GETs {@code pathAndQuery} (relative to the base URL, exactly as the API expects it, with no
   * template variables) and binds the JSON response. Every attempt, retries included, waits for the
   * rate limit.
   *
   * @throws SourceUnavailableException once retries are exhausted or on a non-retryable error
   */
  public <T> T get(String pathAndQuery, Class<T> type) {
    try {
      return retry.execute(
          () -> {
            rateLimiter.acquire();
            return http.get().uri(pathAndQuery).retrieve().body(type);
          });
    } catch (RetryException e) {
      throw new SourceUnavailableException(name + " " + pathAndQuery, e.getCause());
    }
  }
}
