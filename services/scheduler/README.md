# Scheduler service

C# / .NET 10. Finds the lowest-carbon window for flexible electricity use in Great Britain, and shows it on a
dashboard. Design: [ADR-0013](../../docs/adr/0013-scheduler-design.md).

Phase 3 builds it in steps; so far it holds the solution skeleton, the window search (`Core`) and data access (`Data`).

| Project | |
|---|---|
| `src/GridPulse.Scheduler.Core` | The scheduling logic: pure functions over forecasts, no I/O |
| `src/GridPulse.Scheduler.Data` | Where forecasts come from: the forecasting API over HTTP, NESO's from TimescaleDB |
| `src/GridPulse.Scheduler.Api` | ASP.NET Core Minimal API; OpenAPI at `/openapi/v1.json`, Scalar UI at `/scalar` |
| `src/GridPulse.Scheduler.Dashboard` | Blazor Web App (Interactive Server) |
| `tests/*` | xUnit v3 (and bUnit for the dashboard), one project per project above |

## Develop

Needs the .NET 10 SDK (pinned in [global.json](global.json); it's in the Codespace). From this folder:

```bash
dotnet restore GridPulse.Scheduler.slnx --locked-mode
dotnet format GridPulse.Scheduler.slnx --verify-no-changes   # formatting and code style, as CI checks
dotnet build GridPulse.Scheduler.slnx                        # warnings are errors
dotnet test --solution GridPulse.Scheduler.slnx
dotnet run --project src/GridPulse.Scheduler.Api             # then open /scalar
```

- **Warnings are errors**, with the recommended analysers and nullable reference types on, for every project
  ([Directory.Build.props](Directory.Build.props)).
- **Every package version is set once** in [Directory.Packages.props](Directory.Packages.props), and the
  `packages.lock.json` files pin transitive ones. After changing a package, run `dotnet restore` (without
  `--locked-mode`) and commit the updated lock files.
- **Tests run on Microsoft.Testing.Platform** (opted into in `global.json`), so the syntax is
  `dotnet test --solution ...`.

## Finding the window

[`WindowFinder.Find`](src/GridPulse.Scheduler.Core/WindowFinder.cs) takes half-hourly carbon forecasts and a job
(duration, energy, earliest start, deadline) and returns the lowest-carbon window, the earliest one to compare it
with, and the CO2 saved.

- **One pass, O(n).** A job of duration d occupies ceil(d / 30 min) half-hours, the last only partly: a 1h40m job
  uses three and two-thirds. Its intensity is the weighted mean over them. Prefix sums over the forecast make each
  candidate start O(1).
- **Honest edges.** A window never includes a half-hour without a forecast. A deadline beyond the forecast is
  searched as far as the forecast goes and flagged (`LimitedByForecast`). A job that can't fit, or a forecast with no
  complete window, gets a `NotScheduled` with the reason, never a guess. Equally clean windows go to the earliest.
- **Saving with a range.** With P10/P90 forecasts, the saving's range compares like with like (the best window's P10
  with the earliest's P10, and P90 with P90), because forecast errors move together from hour to hour.
- **Tested against brute force.** 500 random cases (gaps, partial half-hours, mixed ranges, random deadlines and
  "now") must match a deliberately naive search that adds up minutes of overlap per half-hour. Planting a bug in the
  partial-half-hour weighting, or an off-by-one in the window sums, fails it.

## Where forecasts come from

Core asks for forecasts through one interface, `ICarbonForecasts`; [Data](src/GridPulse.Scheduler.Data) implements
it, so the scheduling logic never touches HTTP or SQL (ADR-0013).

| Source | Regions | From | Range |
|---|---|---|---|
| `GridPulse` | Great Britain only | The forecasting API (`/v1/forecasts/carbon`), the service that owns those forecasts | P10-P90 |
| `Neso` | GB, its three nations and 14 regions | TimescaleDB: for each half-hour, the latest revision issued by now | none |

- **Resilience:** the forecasting-API client retries 5xx, 408 and 429 with exponential backoff and jitter (never other
  4xx), times out each attempt (3 s) and the whole call (12 s), and has a circuit breaker, so a dead forecasting
  service is skipped quickly rather than holding every request.
- **Least privilege:** the `scheduler` login holds only `scheduler_reader` (migration V8): `SELECT` on
  `carbon_intensity`, nothing else in the schema. Sessions start read-only on top of that.
- **Staleness travels with the data:** the forecasting API's own `stale` flag; for NESO, stale if its newest
  revision is over 6 hours old (two missed regional updates).
- **Configuration** is the stack's environment variables: `POSTGRES_HOST`, `TIMESCALE_PORT`, `POSTGRES_DB`,
  `SCHEDULER_DB_PASSWORD` (required), `SCHEDULER_DB_USER` (default `scheduler`), `FORECASTING_API_URL` (default
  `http://localhost:8000`).
