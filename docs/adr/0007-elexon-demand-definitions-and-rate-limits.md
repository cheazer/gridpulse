# 0007. Elexon data, demand definitions for benchmarking, and per-source rate limits

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

Phase 2 must beat NESO's own day-ahead demand forecast. That comparison is only meaningful if our model predicts
the same quantity NESO forecasts, and GB "demand" has several definitions that differ by thousands of MW.
Elexon's Insights API (no key, BMRS data licence) publishes NESO's forecasts with their real publish times,
alongside the outturns. It publishes no rate limits, and neither does the Carbon Intensity API. Open-Meteo
does publish limits.

## Decision

**Benchmark pairing.** Elexon's API description says NDF "INCLUDES transmission losses, but EXCLUDES
interconnector flows and demand from station transformers and pumped storage units". That is the National
Demand (ND) definition, and INDO is ND's initial outturn. So:

| NESO forecast | Forecasts | Compare against | Notes |
|---|---|---|---|
| NDF (day-ahead) | ND | INDO (live), ND from the NESO portal (history) | **Phase 2 target and benchmark** |
| TSDF (day-ahead) | TSD | ITSDO | Kept for completeness |

**Datasets polled** at :16 and :46 past each hour (outturn lands within 15 minutes of a period ending),
re-fetching the previous settlement day as well as today:

| Dataset | Endpoint | Event | `issued_at` |
|---|---|---|---|
| Demand outturn | `/demand/outturn` | `demand`, series `indo` and `itsdo` | null (actual) |
| Generation by fuel | `/datasets/FUELHH` | `generation_mix`, MW, series `fuelhh` | null |
| System prices | `/balancing/settlement/system-prices/{date}` | `system_price` | null |
| Day-ahead forecasts | `/datasets/NDF`, `/datasets/TSDF?boundary=N` | `demand_forecast` | Elexon's `publishTime` |

Unlike the Carbon Intensity API, NDF and TSDF carry a real publish time, so no revision buckets (ADR-0006)
are needed: each NESO revision is its own event.

**Net flows: schema 1.1.0.** Live FUELHH data has negative interconnector values (GB exporting) and negative
pumped storage (pumping). Generation mix values are therefore *net*. Only `imports` and `storage` may be
negative, enforced by a JSON Schema `if`/`then` and a database check (migration V5). This relaxes a constraint
without breaking any existing event, so it is a BACKWARD-compatible minor version (ADR-0002).

**Rate limits.** Every source goes through one `HttpSource` with a token-bucket limiter, applied to each
attempt including retries. Where the provider publishes no limit we set our own polite one: Elexon 5 requests/s,
Carbon Intensity 2/s. Open-Meteo's published limits (600/min, 5,000/hour, 10,000/day) will be configured with
its poller. Normal polling makes about 10 requests per half-hour, so the limits matter for the backfill
(step 1.8), not for live polling.

**System prices** are rounded to 2 decimal places (net imbalance volume to 3) on ingestion, matching the
database columns, so the event and the stored row agree exactly.

**NESO Data Portal** is only needed for historical ND (to train on and benchmark against the settled values),
so it moves to the backfill step rather than being polled live.

## Consequences

- Phase 2's headline comparison is NDF vs INDO/ND, documented here so the README can state it precisely.
- All Elexon data is national (`GB`), so these topics use a single partition key. That is fine at this volume
  (ADR-0004).
- Each NESO forecast revision is stored, which is about 3,000 rows a day per product: the cost of being able
  to ask what NESO predicted at any given time.
