package dev.gridpulse.ingestion.elexon;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Response shapes of the Elexon Insights API (only the fields we use; others are ignored). */
final class ElexonApi {

  private ElexonApi() {}

  record DemandOutturnResponse(List<DemandOutturn> data) {}

  /** INDO and ITSDO for one settlement period. */
  record DemandOutturn(
      Instant startTime,
      LocalDate settlementDate,
      int settlementPeriod,
      Double initialDemandOutturn,
      Double initialTransmissionSystemDemandOutturn) {}

  record DemandForecastResponse(List<DemandForecast> data) {}

  /** One period of an NDF or TSDF publication; {@code publishTime} is NESO's issue time. */
  record DemandForecast(
      String dataset,
      Instant publishTime,
      Instant startTime,
      LocalDate settlementDate,
      int settlementPeriod,
      Double demand,
      String boundary) {}

  record FuelGenerationResponse(List<FuelGeneration> data) {}

  /** FUELHH: one fuel type's average MW in one settlement period. */
  record FuelGeneration(
      Instant startTime,
      LocalDate settlementDate,
      int settlementPeriod,
      String fuelType,
      Double generation) {}

  record SystemPriceResponse(List<SystemPrice> data) {}

  record SystemPrice(
      Instant startTime,
      LocalDate settlementDate,
      int settlementPeriod,
      BigDecimal systemSellPrice,
      BigDecimal systemBuyPrice,
      BigDecimal netImbalanceVolume) {}
}
