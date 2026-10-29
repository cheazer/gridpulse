package dev.gridpulse.ingestion.openmeteo;

import dev.gridpulse.events.Region;
import dev.gridpulse.ingestion.http.HttpSettings;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the Open-Meteo weather source (ADR-0008). Defaults live in application.yaml.
 *
 * @param revisionInterval forecast revisions are bucketed to this interval
 * @param reanalysisDays complete past days re-fetched from the archive on each reanalysis poll
 */
@ConfigurationProperties("gridpulse.sources.open-meteo")
public record OpenMeteoProperties(
    URI forecastBaseUrl,
    URI archiveBaseUrl,
    boolean pollOnStartup,
    int forecastDays,
    Duration revisionInterval,
    int reanalysisDays,
    List<Location> locations,
    HttpSettings http) {

  /**
   * A weather location. Its id is the event series; its coordinates are configured rather than
   * taken from responses, because Open-Meteo snaps them to its model grid.
   */
  public record Location(String id, double latitude, double longitude, Region region) {}
}
