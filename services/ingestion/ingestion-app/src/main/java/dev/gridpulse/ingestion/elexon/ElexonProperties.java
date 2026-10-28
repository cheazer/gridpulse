package dev.gridpulse.ingestion.elexon;

import dev.gridpulse.ingestion.http.HttpSettings;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the Elexon Insights API source (ADR-0007). Defaults live in application.yaml.
 *
 * @param lookbackDays earlier settlement days to re-fetch on every poll, to pick up revisions
 */
@ConfigurationProperties("gridpulse.sources.elexon")
public record ElexonProperties(
    URI baseUrl, boolean pollOnStartup, int lookbackDays, HttpSettings http) {}
