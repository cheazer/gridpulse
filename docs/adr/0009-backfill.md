# 0009. Backfill: chunked, resumable jobs through the live pipeline

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

Phase 2 needs at least two years of history to train and backtest on. Live polling only looks back hours or a day.
Each source caps how much one request may return (measured on 2026-10-29):

| Source | Dataset | Max per request | Requests for 2 years |
|---|---|---|---|
| Carbon Intensity | national `/intensity/{from}/{to}` | 31 days | ~25 |
| Elexon | demand outturn | 14 days | ~53 |
| Elexon | FUELHH | 7 days | ~105 |
| Elexon | NDF (every revision) | 1 day of publish times (48 revisions, ~2,800 rows) | ~730 |
| Elexon | system prices | 1 settlement date | ~730 |
| Open-Meteo | archive, previous-runs (step 1.8b) | months | tens |
| NESO Data Portal | historic demand (step 1.8b) | one CSV per year | 3 |

A two-year run is about 1,600 requests and several million events, and will take long enough that interruptions
are likely.

## Decision

**Same pipeline as live data.** The backfill is a run mode of `ingestion-app` (Spring profile `backfill`), using the
same clients, mappers and `EventPublisher` as the pollers. Events go through Kafka and the sink like any others.
There is no second write path to keep correct, and idempotent IDs (ADR-0004) make overlaps with live data harmless.

**Jobs and chunks.** Each dataset is a `BackfillJob` that fetches one chunk, a date range sized to the source's
limit. The runner walks every job's chunks oldest first, publishes each chunk, waits for Kafka to acknowledge it,
and then records a checkpoint. Per-source rate limits apply through `HttpSource` (ADR-0007).

**Checkpoints in a compacted Kafka topic,** `gridpulse.backfill.checkpoints`, keyed `job|chunkStart`. On start the
runner reads the topic and skips completed chunks, so an interrupted backfill resumes where it stopped. Kafka was
chosen over a database table because the ingestion app deliberately has no database access. Compaction keeps
one record per chunk however often the backfill runs.

**At-least-once.** A checkpoint is written only after the chunk's events are acknowledged, so a crash between the
two re-publishes that chunk, and the upserts absorb it.

**Failures.** A chunk that still fails after retries stops its job (later chunks would leave a gap behind them),
the other jobs carry on, and the run exits non-zero. Re-running continues from the checkpoints.

**Aggregates.** Continuous aggregates refresh only the last 30 days on a schedule (ADR-0005). Real-time aggregation
reads raw rows only *above* the materialisation watermark, so backfilled history would be invisible in the
daily views. The sink therefore records the time range of every write older than that window and refreshes the
affected aggregates (`refresh_continuous_aggregate`) for that range every minute. This works for any late data,
not just backfills.

**Scope choices.**
- NDF is backfilled with every revision (about 2 million rows), because Phase 2's benchmark needs "what NESO
  had published by the time we forecast". TSDF isn't needed for that benchmark, so it isn't backfilled.
- Regional carbon intensity isn't backfilled. The API only returns final forecasts for past periods, so there
  are no revisions or actuals, and the scheduler only needs regional forecasts going forward.

**Running it:** `docker compose --profile backfill run --rm backfill` (two years by default; see the README).

## Consequences

- One code path for live and historical data: a mapper fix applies to both, and the golden-example tests cover both.
- The backfill runs at the speed of the slowest rate limit and of the sink. Both are fine for a one-off load.
- The checkpoint topic is the backfill's only state. Deleting it forces a full re-run, which is safe but slow.
