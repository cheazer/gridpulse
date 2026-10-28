# 0002. Redpanda locally, JSON Schema contracts with a schema registry

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

Ingestion publishes normalised events that a separate sink consumes, and other consumers may follow. We need a
durable log with replay (for backfills and reprocessing) and partitioning (to scale consumers). Producers and
consumers are written in different languages, so the event contract must be language-neutral and enforced.

## Options considered

**Broker**

1. **Apache Kafka (KRaft) in Docker**: the reference implementation, but heavier on a laptop (JVM, more memory)
   and needs a separately run schema registry.
2. **Redpanda**: Kafka-API compatible, a single C++ binary, low memory, with a built-in Schema Registry and a web
   console. Testcontainers has a Redpanda module.
3. **RabbitMQ / Redis Streams**: simpler, but lacks Kafka's partitioned log semantics, consumer-group rebalancing
   and long retention for replay, which are central to the design.

**Schema format**

1. **Avro**: compact binary encoding and mature registry tooling, but generated C# and Python code is clunky and
   messages aren't human-readable when debugging.
2. **Protobuf**: excellent codegen everywhere, but optional/nullable semantics need care and the files are less
   self-describing for data validation (ranges, patterns).
3. **JSON Schema**: human-readable payloads, validators in every language, and expressive constraints (UTC
   timestamp patterns, settlement period 1–50). Larger messages than binary formats.

## Decision

- **Redpanda** for local development and CI. The code uses only the standard Kafka client APIs, so production
  could run on managed Kafka (e.g. MSK, Confluent Cloud) without code changes.
- **JSON Schema (draft-07)** for event contracts, stored in `schemas/events/v1/` and registered in the schema
  registry. Draft-07 is chosen over 2020-12 because it has the widest validator and registry support.
- Compatibility mode **BACKWARD** per subject: a new schema version must be able to read data written with the
  previous one (so only optional fields may be added).
- **Producers validate strictly** before publishing (closed schemas, `additionalProperties: false`); **consumers
  deserialise leniently** (ignore unknown fields). This lets producers roll forward with additive changes without
  breaking consumers that haven't been redeployed yet.

## Consequences

- Message size is larger than with Avro. At our volume (thousands of events per hour, not millions per second)
  this is irrelevant; it would be revisited if throughput became a constraint.
- Breaking changes (renaming or removing a field, changing a type) require a new major version: new schema
  folder `v2/` and new topics `*.v2`, with a period of dual-publishing.
- The per-type schemas `$ref` the shared envelope definitions. These are registered as schema-registry
  references (envelope first, then dependants); verified against Redpanda v26.2 by
  `deploy/compose/scripts/smoke-test.sh`. Had that not worked, the fallback was to bundle each schema into one
  self-contained document at build time.
