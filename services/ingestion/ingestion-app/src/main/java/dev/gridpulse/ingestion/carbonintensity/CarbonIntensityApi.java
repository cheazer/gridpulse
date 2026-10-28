package dev.gridpulse.ingestion.carbonintensity;

import java.util.List;

/**
 * Response shapes of api.carbonintensity.org.uk (only the fields we use; others are ignored).
 * Timestamps are kept as strings here because the API omits seconds ({@code 2026-10-28T18:00Z});
 * {@link CarbonIntensityMapper} parses them.
 */
final class CarbonIntensityApi {

  private CarbonIntensityApi() {}

  record NationalResponse(List<NationalPeriod> data) {}

  record NationalPeriod(String from, String to, NationalIntensity intensity) {}

  /**
   * @param actual null until the period has ended and been measured
   */
  record NationalIntensity(Integer forecast, Integer actual, String index) {}

  record RegionalResponse(List<RegionalPeriod> data) {}

  record RegionalPeriod(String from, String to, List<RegionData> regions) {}

  record RegionData(int regionid, String shortname, RegionalIntensity intensity) {}

  /** Regional data is forecast-only. */
  record RegionalIntensity(Integer forecast, String index) {}
}
