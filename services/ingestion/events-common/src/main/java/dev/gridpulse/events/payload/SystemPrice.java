package dev.gridpulse.events.payload;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Imbalance (cash-out) price for one settlement period. BigDecimal because these are money; prices
 * can be negative.
 *
 * @param netImbalanceVolumeMwh may be null when the source hasn't published it
 */
public record SystemPrice(
    BigDecimal systemSellPriceGbpPerMwh,
    BigDecimal systemBuyPriceGbpPerMwh,
    BigDecimal netImbalanceVolumeMwh)
    implements Payload {

  public SystemPrice {
    Objects.requireNonNull(systemSellPriceGbpPerMwh, "systemSellPriceGbpPerMwh");
    Objects.requireNonNull(systemBuyPriceGbpPerMwh, "systemBuyPriceGbpPerMwh");
  }
}
