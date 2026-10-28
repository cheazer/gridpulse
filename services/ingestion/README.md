# Ingestion service (Java 21)

Polls public UK grid data sources, normalises records into the shared event schema and publishes them to
Kafka; a separate sink writes them to TimescaleDB. Built with Gradle; shared build settings live in the
`build-logic` convention plugin, and dependency versions in [gradle/libs.versions.toml](gradle/libs.versions.toml).

| Module | Kind | Purpose |
|---|---|---|
| `events-common` | library | The event model as Java records, JSON codec, JSON Schema validation, deterministic event IDs and settlement-period maths |
| `ingestion-app` | Spring Boot app | Polls the Carbon Intensity API every half-hour and publishes validated events to Kafka |
| `timescale-sink` | Spring Boot app | Consumes every event topic and upserts batches into TimescaleDB, dead-lettering undecodable records |

```bash
cd services/ingestion
./gradlew build          # compile (warnings are errors), check formatting, unit + Testcontainers tests (needs Docker)
./gradlew spotlessApply  # fix formatting
```

Both apps build from one [Dockerfile](Dockerfile) (`--build-arg APP=ingestion-app` or `timescale-sink`) and run
as part of `docker compose up` from the repo root. Polling, revision buckets and delivery guarantees are explained
in [ADR-0006](../../docs/adr/0006-polling-and-forecast-revisions.md).

## ingestion-app

- **`CarbonIntensityClient`** reads 48 hours of national and regional data per call, retrying network errors,
  5xx and 429 with exponential backoff and jitter (Spring Framework 7's `RetryTemplate`), and failing fast on
  other 4xx.
- **`CarbonIntensityMapper`** is a pure function from API response + poll time to events: measured periods
  become actuals, future periods become forecast revisions bucketed to 30 minutes (national) or 3 hours
  (regional).
- **`EventPublisher`** validates each event and sends it keyed by region, or to the `.dlq` topic with the
  reasons in a header. A poll succeeds only once Kafka has acknowledged every send.
- Tests run against real API responses recorded in `src/test/resources/fixtures`, served by a small JDK HTTP
  server that rejects malformed paths the way the real API does.

## timescale-sink

- **`EventConsumer`** reads batches from every event topic. **`EventWriter`** upserts them in one transaction
  (`ON CONFLICT (event_id, valid_from) DO UPDATE`), and offsets are committed only after it returns.
- Undecodable records go straight to `<topic>.dlq`. Database errors retry with backoff indefinitely, so an
  outage pauses consumption rather than losing data.
- `SinkIT` runs against real Redpanda and TimescaleDB containers with the real `db/migrations` applied. It
  checks every golden event lands, that re-delivery changes nothing, and that a poison record is dead-lettered
  without stopping the partition.

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
