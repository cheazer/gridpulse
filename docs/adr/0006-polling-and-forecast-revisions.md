# 0006. Polling cadence, forecast revisions and delivery guarantees

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

The Carbon Intensity API's `fw48h` endpoints return 96 half-hours (48 hours) per call: past periods with an
actual value, future ones with a forecast that NESO revises every half-hour. It publishes no issue time for its forecasts, no rate limits and
no change feed, so we poll. The regional endpoint returns 17 regions (plus its own GB row) per half-hour.

If every poll stored a new revision of every future period, regional forecasts alone would be
17 regions × 96 periods × 48 polls ≈ 78,000 rows a day, nearly all repeating the previous poll.

## Decision

**Polling.** Poll at 2 minutes past each half-hour (new actuals appear shortly after a period ends), plus once
at start-up. Each poll re-fetches from 2 hours back. Late or corrected actuals are picked up, and a missed poll
is recovered by the next one. Idempotent IDs (ADR-0004) make the overlap free.

**Actuals vs forecasts.** A period with an actual value becomes an *actual* event (`issued_at = null`), carrying
the final forecast too. A period starting after the poll without an actual becomes a *forecast* event. Past
periods still waiting for their actual are skipped until it arrives.

**Revision buckets.** `issued_at` is the poll time *truncated to a revision interval*: 30 minutes nationally
(needed to benchmark NESO's day-ahead carbon forecast honestly) and 3 hours regionally (only the latest is
needed for scheduling and the heatmap). Re-polls inside one bucket produce the same event ID, so the stored
revision is simply updated to the latest value seen in that bucket. This bounds regional volume to about
14,000 rows a day without any state in the poller. `issued_at` therefore means "the revision bucket in which
this forecast was last observed", not an issue time published by NESO.

**Regional GB row.** Skipped: the national endpoint is the authoritative GB series, and storing both under the
same identity would make them overwrite each other.

**Retries.** HTTP calls retry on network errors, 5xx and 429 with exponential backoff plus jitter (4 retries,
1 s doubling to 30 s). Other 4xx responses are bugs on our side and fail immediately.

**Publishing.** Producers validate each event against its JSON Schema and `EventRules` before sending.
Events that fail go to the topic's `.dlq` with the reasons in a header, and nothing invalid reaches the main
topic. The producer uses `acks=all` and idempotence. A poll succeeds only once Kafka has acknowledged every
send, so if Kafka is down the poll fails and the next poll's 2-hour lookback recovers the data.

**Consuming.** The TimescaleDB sink is a separate deployable. It reads batches, upserts them in one database
transaction, and commits Kafka offsets only after the database commit, so delivery is at-least-once and the
results are effectively-once. A record that can't be decoded goes straight to the DLQ without retrying.
Database errors are retried indefinitely with backoff, so an outage pauses consumption instead of discarding
data.

**Wire format.** Plain JSON, with no schema-registry framing. The registry holds the contracts and enforces
compatibility (ADR-0002), and producers validate locally against the same files. Registry-framed
serialisers would add a vendor dependency to every client for no gain while each topic carries a single major
version.

## Consequences

- No poller state, no deduplication cache: correctness comes from deterministic IDs and upserts.
- A forecast's history has 30-minute (national) or 3-hour (regional) resolution, not every NESO revision.
- An outage longer than the 2-hour lookback leaves a gap until the backfill command (step 1.8) is run.
- Consumers trust producers to validate. If an unvalidated producer ever appeared, the sink would also need to
  validate before writing.
