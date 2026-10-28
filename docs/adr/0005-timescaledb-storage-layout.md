# 0005. TimescaleDB storage layout: one typed hypertable per event type

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

The sink writes six event types into TimescaleDB. The forecasting service reads long, regular time series
(e.g. two years of half-hourly demand plus weather), and the scheduler and dashboard read recent values and
aggregates. Writes must be idempotent (ADR-0004), and every forecast revision must be kept.

## Options considered

1. **One generic `events` table with a JSONB payload.** One code path in the sink and no migration when a
   payload field is added. But every read has to unpack JSON, types and constraints aren't enforced by the
   database, continuous aggregates over JSON expressions are awkward, and columnar compression works poorly
   on JSONB.
2. **One typed hypertable per event type.** Plain SQL for readers, database-enforced types and checks, simple
   continuous aggregates, good compression. A new payload field needs a migration (which a schema change needs
   anyway).
3. **Typed tables plus a raw JSONB archive table.** Allows re-deriving typed rows from the original events, at
   the cost of storing everything twice. Kafka plus the public APIs (which keep history) already give us replay.

## Decision

Option 2, in a dedicated `grid` schema, with migrations run by **Flyway** as a one-shot job (a Compose
service locally, a Kubernetes Job later) so the schema doesn't belong to any one service's code.

- Every table carries the envelope columns (`event_id`, `valid_from`, `valid_to`, settlement date/period,
  `issued_at`, `ingested_at`, `source`, `series`, `region`, `schema_version`) plus typed payload columns.
- **Primary key `(event_id, valid_from)`.** The sink upserts on it. `valid_from` must be included because
  unique indexes on a hypertable have to contain the partitioning column.
- **Generation mix is stored long**: one row per fuel per half-hour, keyed `(event_id, source_fuel_code, valid_from)`,
  so "sum by fuel" is a plain `GROUP BY`. The sink replaces all rows for an `event_id` in one transaction, so a
  fuel dropped from a corrected record doesn't linger.
- **Chunk interval 30 days**, not the 7-day default. Our tables are small (thousands of rows per week), and many
  tiny chunks add planning overhead without benefit.
- **Continuous aggregates** for the dashboard: daily carbon intensity by region, daily demand, and daily
  generation energy by fuel. Buckets are `Europe/London` days, so a clock-change day is one 23- or 25-hour
  bucket. They run in real-time mode, so the newest data that hasn't been materialised yet is still included.
  "Latest forecast per region" (the heatmap) is a plain indexed query, not an aggregate, because it depends
  on revisions rather than averages.
- Generation is aggregated as **energy** (`MW × 0.5 h`), not average MW: one fuel can have several source codes
  per half-hour (gas = CCGT + OCGT), so averaging rows would understate it.
- **Columnstore (compression)** on chunks older than 90 days, segmented by the column queries filter on
  (`region`, `series` or `fuel`).
- **Retention:** raw rows are dropped after 5 years. Aggregates are kept indefinitely; their refresh window
  (the last 30 days) is far shorter than the retention period, so dropping old raw chunks never deletes
  aggregated history.
- **Least privilege:** two `NOLOGIN` roles, `grid_writer` (the sink) and `grid_reader` (forecasting,
  scheduler). Login users are created per environment and granted one of them; no passwords live in migrations.

## Consequences

- Adding a payload field means a new migration plus a sink change, in the same PR as the schema change.
- Historical backfills land outside the 30-day refresh window, so the backfill command must call
  `refresh_continuous_aggregate` for the range it loaded (step 1.8).
- Writing into compressed chunks works but is slower; the backfill should run before the columnstore policy
  has compressed that range, or accept the slower upsert path.
- Continuous aggregates are created `WITH NO DATA` so migrations can run inside Flyway's transaction;
  the refresh policy fills them.
