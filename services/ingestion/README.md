# Ingestion service (Java 21)

Polls public UK grid data sources, normalises records into the shared event schema and publishes them to
Kafka; a separate sink writes them to TimescaleDB. Built with Gradle; shared build settings live in the
`build-logic` convention plugin, and dependency versions in [gradle/libs.versions.toml](gradle/libs.versions.toml).

| Module | Purpose |
|---|---|
| `events-common` | The event model as Java records, JSON codec, JSON Schema validation, deterministic event IDs and settlement-period maths |

```bash
cd services/ingestion
./gradlew build          # compile (warnings are errors), check formatting, run tests
./gradlew spotlessApply  # fix formatting
```

## events-common

- **`GridEvent`** is the envelope plus a sealed `Payload` (one record per event type). `GridEvent.create(...)`
  derives the event ID, so producers can't get it wrong.
- **`EventCodec`** reads leniently (ignores unknown fields) and **`EventValidator`** checks strictly against the
  JSON Schemas. Producers validate before publishing; consumers keep working when a newer producer adds an
  optional field (ADR-0002).
- **`EventRules`** checks what JSON Schema can't: event ID derivation, interval order and settlement period.
- **`SettlementPeriods`** maps instants to GB settlement periods, including the 46- and 50-period clock-change days.

The model is hand-written records rather than generated from the schemas: JSON Schema code generators handle
`allOf` composition (used for the envelope) poorly. Instead, `GoldenExamplesTest` runs every golden example in
`schemas/events/v1/examples` through the validator, the codec and the rules, and checks that re-encoding
reproduces the original JSON. The same files drive the Python validator, so the two implementations can't
drift apart unnoticed.
