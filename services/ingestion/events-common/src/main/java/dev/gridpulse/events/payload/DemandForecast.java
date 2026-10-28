package dev.gridpulse.events.payload;

import dev.gridpulse.events.payload.Demand.DemandType;
import java.util.Objects;

/**
 * A third-party demand forecast for one half-hour, e.g. NESO's day-ahead (product NDF). Each
 * revision is its own event, distinguished by the envelope's {@code issued_at}.
 */
public record DemandForecast(DemandType demandType, double forecastMw, String product)
    implements Payload {

  public DemandForecast {
    Objects.requireNonNull(demandType, "demandType");
    Objects.requireNonNull(product, "product");
    if (forecastMw < 0) {
      throw new IllegalArgumentException("forecast demand cannot be negative: " + forecastMw);
    }
  }
}
