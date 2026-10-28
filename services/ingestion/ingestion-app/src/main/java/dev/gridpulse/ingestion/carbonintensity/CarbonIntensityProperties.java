package dev.gridpulse.ingestion.carbonintensity;

import dev.gridpulse.ingestion.http.HttpSettings;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the NESO Carbon Intensity API source (ADR-0006). Defaults live in application.yaml.
 *
 * @param lookback how far back each poll re-fetches, so late actuals and missed polls are recovered
 * @param nationalRevisionInterval forecast revisions are bucketed to this interval nationally
 * @param regionalRevisionInterval and to this interval regionally
 */
@ConfigurationProperties("gridpulse.sources.carbon-intensity")
public record CarbonIntensityProperties(
    URI baseUrl,
    boolean pollOnStartup,
    Duration lookback,
    Duration nationalRevisionInterval,
    Duration regionalRevisionInterval,
    HttpSettings http) {}
