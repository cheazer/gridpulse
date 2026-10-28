package dev.gridpulse.events.payload;

import java.util.Objects;

/** Measured demand for one half-hour. */
public record Demand(DemandType demandType, double demandMw) implements Payload {

  public Demand {
    Objects.requireNonNull(demandType, "demandType");
    if (demandMw < 0) {
      throw new IllegalArgumentException("demand cannot be negative: " + demandMw);
    }
  }

  /**
   * Which definition of demand a figure uses. They include different flows, so forecasts must be
   * compared like-for-like.
   */
  public enum DemandType {
    /** National Demand. */
    ND,
    /** Transmission System Demand. */
    TSD,
    /** Initial National Demand Outturn. */
    INDO,
    /** Initial Transmission System Demand Outturn. */
    ITSDO
  }
}
