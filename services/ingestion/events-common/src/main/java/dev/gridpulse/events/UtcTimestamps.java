package dev.gridpulse.events;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** The canonical timestamp form hashed into event IDs: {@code 2026-10-27T18:00:00Z}. */
public final class UtcTimestamps {

  private static final DateTimeFormatter CANONICAL =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private UtcTimestamps() {}

  /**
   * @throws IllegalArgumentException if the instant has sub-second precision, which the canonical
   *     form can't represent; silently truncating it would give two different instants the same ID
   */
  public static String canonical(Instant instant) {
    if (instant.getNano() != 0) {
      throw new IllegalArgumentException("identity timestamps must be whole seconds: " + instant);
    }
    return CANONICAL.format(instant);
  }
}
