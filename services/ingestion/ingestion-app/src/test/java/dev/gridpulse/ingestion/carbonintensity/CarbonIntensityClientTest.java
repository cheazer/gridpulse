package dev.gridpulse.ingestion.carbonintensity;

import static dev.gridpulse.ingestion.carbonintensity.Fixtures.POLLED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.gridpulse.ingestion.http.SourceUnavailableException;
import dev.gridpulse.ingestion.testing.FixtureServer;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class CarbonIntensityClientTest {

  private final FixtureServer api = Fixtures.server();
  private final CarbonIntensityClient client =
      new CarbonIntensityClient(RestClient.builder(), Fixtures.properties(api.baseUrl()));

  @AfterEach
  void stopServer() {
    api.close();
  }

  @Test
  void readsNationalAndRegionalResponses() {
    assertThat(client.national(POLLED_AT)).hasSize(96);
    assertThat(client.regional(POLLED_AT)).hasSize(96);
  }

  @Test
  void sendsTheTimestampUnencodedInTheApiFormat() {
    client.national(POLLED_AT);
    client.regional(POLLED_AT);

    // Regression: a percent-encoded ':' (20%3A16Z) gets a 400 from the real API.
    assertThat(api.requestedPaths())
        .containsExactly(
            "/intensity/2026-10-28T20:16Z/fw48h", "/regional/intensity/2026-10-28T20:16Z/fw48h");
  }

  @Test
  void historyRequestsAFromToRange() {
    assertThat(
            client.national(
                Instant.parse("2024-10-01T00:00:00Z"), Instant.parse("2024-10-31T00:00:00Z")))
        .hasSize(96);

    assertThat(api.requestedPaths())
        .containsExactly("/intensity/2024-10-01T00:00Z/2024-10-31T00:00Z");
  }

  @Test
  void retriesServerErrorsAndRateLimitingThenSucceeds() {
    api.failNext(503, 429, 500);

    assertThat(client.national(POLLED_AT)).hasSize(96);
    assertThat(api.requestCount()).isEqualTo(4);
  }

  @Test
  void givesUpAfterTheConfiguredRetries() {
    api.failNext(503, 503, 503, 503, 503);

    assertThatExceptionOfType(SourceUnavailableException.class)
        .isThrownBy(() -> client.national(POLLED_AT))
        .withCauseInstanceOf(HttpServerErrorException.class);
    assertThat(api.requestCount()).isEqualTo(4); // first attempt + 3 retries
  }

  @Test
  void retriesAResponseCutOffMidBody() {
    // Regression: a download dropped mid-body surfaced as a non-retryable RestClientException and
    // stopped a backfill job.
    api.failNext(FixtureServer.TRUNCATED);

    assertThat(client.national(POLLED_AT)).hasSize(96);
    assertThat(api.requestCount()).isEqualTo(2);
  }

  @Test
  void doesNotRetryClientErrors() {
    api.failNext(400);

    assertThatExceptionOfType(SourceUnavailableException.class)
        .isThrownBy(() -> client.national(POLLED_AT))
        .withCauseInstanceOf(HttpClientErrorException.class);
    assertThat(api.requestCount()).isEqualTo(1);
  }
}
