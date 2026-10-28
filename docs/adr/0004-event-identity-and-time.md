# 0004. Deterministic event IDs, UTC timestamps, settlement periods

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

- Pollers re-fetch overlapping windows (to pick up late or revised data), retries can publish the same record
  twice, and Kafka delivers **at least once**. Duplicates must not create duplicate rows.
- Forecasts are **revised**: NESO publishes several forecasts for the same target half-hour. To backtest honestly
  ("what did NESO predict a day ahead?") every revision must be kept, not overwritten.
- GB electricity is traded in 30-minute **settlement periods** in UK local time. A normal day has 48; the day the
  clocks go forward has 46 and the day they go back has 50. Local-time arithmetic breaks twice a year.

## Decision

1. **All timestamps are UTC**, serialised as `YYYY-MM-DDTHH:MM:SSZ` (enforced by a schema pattern). Settlement
   date and period are carried as **metadata** alongside, never used for arithmetic.
2. **`event_id` is a UUIDv5** (name-based, SHA-1) under the fixed GridPulse namespace
   `4402d35f-92c6-4368-bf94-8f364945a3e4`, over the name:

   ```
   {source}|{event_type}|{series}|{region}|{valid_from}|{issued_at or ""}
   ```

   `series` distinguishes multiple series of the same type from one source (e.g. `indo` vs `itsdo` demand, or a
   weather location). `issued_at` is part of the identity, so each forecast revision is a distinct event.
3. The sink writes with `INSERT … ON CONFLICT (event_id, valid_from) DO UPDATE`, so a re-delivered event is a
   no-op and a corrected *actual* value replaces the earlier one. (`valid_from` is in the key because unique
   indexes on a TimescaleDB hypertable must include its time partitioning column.)
4. Kafka message key = `region`, so all events for one region stay ordered within a partition.

## Consequences

- At-least-once delivery + idempotent writes gives **effectively-once results** without Kafka transactions.
- Every language must derive the same ID. The golden examples in `schemas/events/v1/examples/` include the
  expected `event_id`, and each service's tests check its implementation against them.
- Storing every forecast revision grows the forecast tables by roughly the number of revisions per day; at our
  volume that's acceptable and is managed with retention policies.
- Region-keyed partitioning means the national (`GB`) stream lands on a single partition. This is fine at our
  volume; a hot key would need a compound key (e.g. region + series).
