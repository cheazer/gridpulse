package dev.gridpulse.ingestion.elexon;

import dev.gridpulse.ingestion.elexon.ElexonApi.DemandForecast;
import dev.gridpulse.ingestion.elexon.ElexonApi.DemandForecastResponse;
import dev.gridpulse.ingestion.elexon.ElexonApi.DemandOutturn;
import dev.gridpulse.ingestion.elexon.ElexonApi.DemandOutturnResponse;
import dev.gridpulse.ingestion.elexon.ElexonApi.FuelGeneration;
import dev.gridpulse.ingestion.elexon.ElexonApi.FuelGenerationResponse;
import dev.gridpulse.ingestion.elexon.ElexonApi.SystemPrice;
import dev.gridpulse.ingestion.elexon.ElexonApi.SystemPriceResponse;
import dev.gridpulse.ingestion.http.HttpSource;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The Elexon Insights endpoints GridPulse uses (ADR-0007). Dates are UK settlement dates. */
@Component
public class ElexonClient {

  /** NESO's day-ahead demand forecasts. */
  public enum DayAheadProduct {
    /** National Demand Forecast: forecasts ND, comparable with INDO. */
    NDF("/datasets/NDF"),
    /** Transmission System Demand Forecast: forecasts TSD, comparable with ITSDO. */
    TSDF("/datasets/TSDF?boundary=N");

    private final String path;

    DayAheadProduct(String path) {
      this.path = path;
    }
  }

  private final HttpSource http;

  public ElexonClient(RestClient.Builder builder, ElexonProperties properties) {
    this.http = new HttpSource("Elexon Insights API", builder, properties.baseUrl(), properties.http());
  }

  public List<DemandOutturn> demandOutturn(LocalDate from, LocalDate to) {
    return http.get(
            "/demand/outturn?settlementDateFrom=%s&settlementDateTo=%s".formatted(from, to),
            DemandOutturnResponse.class)
        .data();
  }

  public List<FuelGeneration> fuelGeneration(LocalDate from, LocalDate to) {
    return http.get(
            "/datasets/FUELHH?settlementDateFrom=%s&settlementDateTo=%s".formatted(from, to),
            FuelGenerationResponse.class)
        .data();
  }

  public List<SystemPrice> systemPrices(LocalDate settlementDate) {
    return http.get(
            "/balancing/settlement/system-prices/%s".formatted(settlementDate),
            SystemPriceResponse.class)
        .data();
  }

  /** The latest published forecast for the product. */
  public List<DemandForecast> latestDayAheadForecast(DayAheadProduct product) {
    return http.get(product.path, DemandForecastResponse.class).data();
  }
}
