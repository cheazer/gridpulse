package dev.gridpulse.events;

import com.fasterxml.jackson.annotation.JsonValue;
import dev.gridpulse.events.payload.CarbonIntensity;
import dev.gridpulse.events.payload.Demand;
import dev.gridpulse.events.payload.DemandForecast;
import dev.gridpulse.events.payload.GenerationMix;
import dev.gridpulse.events.payload.Payload;
import dev.gridpulse.events.payload.SystemPrice;
import dev.gridpulse.events.payload.Weather;

/** The kinds of event GridPulse publishes, each with its Kafka topic and payload type. */
public enum EventType {
  CARBON_INTENSITY("carbon_intensity", "carbon-intensity", CarbonIntensity.class),
  GENERATION_MIX("generation_mix", "generation-mix", GenerationMix.class),
  DEMAND("demand", "demand", Demand.class),
  DEMAND_FORECAST("demand_forecast", "demand-forecast", DemandForecast.class),
  SYSTEM_PRICE("system_price", "system-price", SystemPrice.class),
  WEATHER("weather", "weather", Weather.class);

  private final String wireName;
  private final String slug;
  private final Class<? extends Payload> payloadType;

  EventType(String wireName, String slug, Class<? extends Payload> payloadType) {
    this.wireName = wireName;
    this.slug = slug;
    this.payloadType = payloadType;
  }

  /** The {@code event_type} value on the wire. */
  @JsonValue
  public String wireName() {
    return wireName;
  }

  public Class<? extends Payload> payloadType() {
    return payloadType;
  }

  public String topic() {
    return "gridpulse." + slug + ".v1";
  }

  public String deadLetterTopic() {
    return topic() + ".dlq";
  }

  /** File name of this type's JSON Schema under {@code schemas/events/v1}. */
  public String schemaFile() {
    return slug + ".schema.json";
  }

  /** Every event topic (not the dead-letter topics), e.g. for a consumer subscribing to all. */
  public static String[] allTopics() {
    String[] topics = new String[values().length];
    for (EventType type : values()) {
      topics[type.ordinal()] = type.topic();
    }
    return topics;
  }

  public static EventType fromWireName(String wireName) {
    for (EventType type : values()) {
      if (type.wireName.equals(wireName)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown event_type: " + wireName);
  }
}
