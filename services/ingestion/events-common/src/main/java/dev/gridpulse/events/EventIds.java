package dev.gridpulse.events;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

/**
 * Deterministic event IDs (ADR-0004): UUIDv5 over {@code
 * source|event_type|series|region|valid_from|issued_at}. Every service derives the same ID for the
 * same record, which is what makes re-polling and re-delivery idempotent.
 */
public final class EventIds {

  /** The GridPulse UUIDv5 namespace. Changing it would change every event ID. */
  public static final UUID NAMESPACE = UUID.fromString("4402d35f-92c6-4368-bf94-8f364945a3e4");

  private EventIds() {}

  public static UUID derive(
      Source source,
      EventType eventType,
      String series,
      Region region,
      Instant validFrom,
      Instant issuedAt) {
    String name =
        String.join(
            "|",
            source.wireName(),
            eventType.wireName(),
            series,
            region.slug(),
            UtcTimestamps.canonical(validFrom),
            issuedAt == null ? "" : UtcTimestamps.canonical(issuedAt));
    return uuidV5(NAMESPACE, name);
  }

  /**
   * RFC 9562 name-based UUID using SHA-1. The JDK only has {@link UUID#nameUUIDFromBytes}, which is
   * version 3 (MD5) and takes no namespace.
   */
  static UUID uuidV5(UUID namespace, String name) {
    MessageDigest sha1;
    try {
      sha1 = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 is required by every Java platform", e);
    }
    sha1.update(
        ByteBuffer.allocate(16)
            .putLong(namespace.getMostSignificantBits())
            .putLong(namespace.getLeastSignificantBits())
            .array());
    byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
    hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
    hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // IETF variant
    ByteBuffer bits = ByteBuffer.wrap(hash, 0, 16);
    return new UUID(bits.getLong(), bits.getLong());
  }
}
