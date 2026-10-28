package dev.gridpulse.events;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Validates event JSON against the v1 JSON Schemas packaged from {@code schemas/events/v1}.
 * Producers call this before publishing (ADR-0002). Thread-safe; build one and reuse it.
 */
public final class EventValidator {

  private static final String SCHEMA_BASE_ID = "https://gridpulse.example/schemas/events/v1/";
  private static final String SCHEMA_CLASSPATH = "classpath:gridpulse/schemas/v1/";

  private final Map<EventType, Schema> schemas = new EnumMap<>(EventType.class);

  public EventValidator() {
    // Schemas $ref each other by $id URL; resolve those from the classpath, never the network.
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_7,
            builder ->
                builder.schemaIdResolvers(
                    resolvers -> resolvers.mapPrefix(SCHEMA_BASE_ID, SCHEMA_CLASSPATH)));
    for (EventType type : EventType.values()) {
      Schema schema = registry.getSchema(SchemaLocation.of(SCHEMA_BASE_ID + type.schemaFile()));
      // Fail at start-up, not on the first event, if a schema is broken.
      schema.initializeValidators();
      schemas.put(type, schema);
    }
  }

  /** Returns human-readable violations; an empty list means the event is valid. */
  public List<String> validate(JsonNode event) {
    JsonNode type = event.get("event_type");
    if (type == null || !type.isString()) {
      return List.of("event_type is missing");
    }
    EventType eventType;
    try {
      eventType = EventType.fromWireName(type.asString());
    } catch (IllegalArgumentException e) {
      return List.of(e.getMessage());
    }
    return schemas.get(eventType).validate(event).stream().map(Error::toString).toList();
  }
}
