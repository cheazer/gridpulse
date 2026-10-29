# Architecture Decision Records

Each ADR captures one significant decision: the context, the options considered, what we chose and what it costs us.
ADRs are immutable once accepted; a later ADR can supersede an earlier one.

Format: [Michael Nygard's template](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions), see [0000-template.md](0000-template.md).

| # | Title | Status |
|---|---|---|
| [0001](0001-monorepo.md) | Monorepo with one folder per service | Accepted |
| [0002](0002-redpanda-and-json-schema.md) | Redpanda locally, JSON Schema contracts with a schema registry | Accepted |
| [0003](0003-runtime-versions.md) | Runtime versions: Java 21, .NET 10, Python 3.12 | Accepted |
| [0004](0004-event-identity-and-time.md) | Deterministic event IDs, UTC timestamps, settlement periods | Accepted |
| [0005](0005-timescaledb-storage-layout.md) | TimescaleDB storage layout: one typed hypertable per event type | Accepted |
| [0006](0006-polling-and-forecast-revisions.md) | Polling cadence, forecast revisions and delivery guarantees | Accepted |
| [0007](0007-elexon-demand-definitions-and-rate-limits.md) | Elexon data, demand definitions for benchmarking, and per-source rate limits | Accepted |
| [0008](0008-weather-forecasts-and-reanalysis.md) | Weather: forecast revisions and reanalysis, kept apart | Accepted |
