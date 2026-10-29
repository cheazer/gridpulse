package dev.gridpulse.ingestion.neso;

import dev.gridpulse.ingestion.http.HttpSettings;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the NESO Data Portal source, used by the backfill (ADR-0009).
 *
 * @param historicDemandDataset CKAN dataset holding one demand CSV per year
 */
@ConfigurationProperties("gridpulse.sources.neso")
public record NesoProperties(URI baseUrl, String historicDemandDataset, HttpSettings http) {}
