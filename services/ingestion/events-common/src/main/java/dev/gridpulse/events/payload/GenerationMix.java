package dev.gridpulse.events.payload;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Generation by fuel for one half-hour, in MW (Elexon FUELHH) or percent (Carbon Intensity). */
public record GenerationMix(Unit unit, List<FuelReading> fuels) implements Payload {

  public GenerationMix {
    Objects.requireNonNull(unit, "unit");
    fuels = List.copyOf(fuels);
    if (fuels.isEmpty()) {
      throw new IllegalArgumentException("generation mix needs at least one fuel");
    }
  }

  /**
   * @param fuel normalised category used across sources
   * @param sourceFuelCode the code exactly as the source reported it (e.g. CCGT, INTFR)
   * @param value net value; negative only for imports (net export) and storage (pumping)
   */
  public record FuelReading(Fuel fuel, String sourceFuelCode, double value) {
    public FuelReading {
      Objects.requireNonNull(fuel, "fuel");
      Objects.requireNonNull(sourceFuelCode, "sourceFuelCode");
      if (value < 0 && !fuel.isNetFlow()) {
        throw new IllegalArgumentException(fuel.wireName() + " cannot be negative: " + value);
      }
    }
  }

  public enum Fuel {
    GAS,
    COAL,
    OIL,
    NUCLEAR,
    WIND,
    SOLAR,
    HYDRO,
    BIOMASS,
    STORAGE,
    IMPORTS,
    OTHER;

    @JsonValue
    public String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** Net flows go negative: interconnectors when GB exports, storage while pumping. */
    public boolean isNetFlow() {
      return this == IMPORTS || this == STORAGE;
    }
  }

  public enum Unit {
    MW("MW"),
    PERCENT("percent");

    private final String wireName;

    Unit(String wireName) {
      this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
      return wireName;
    }
  }
}
