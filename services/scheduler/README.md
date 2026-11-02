# Scheduler service

C# / .NET 10. Finds the lowest-carbon window for flexible electricity use in Great Britain, and shows it on a
dashboard. Design: [ADR-0013](../../docs/adr/0013-scheduler-design.md).

Phase 3 builds it in steps; so far it holds the window search (`Core`), data access (`Data`) and the scheduling API (`Api`).

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
- **Saving with a range.** With P10/P90 forecasts, the saving is worked out like with like at each quantile level
  (P10 with P10, P50 with P50, P90 with P90), because forecast errors move together from hour to hour, and the range
  spans all three. Bands are often lopsided, so the P10 and P90 savings alone can both fall below the expected one; a
  range that left the expected saving out would contradict itself (found on live data, now a tested invariant).
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

## The API

```bash
docker compose up -d scheduler-api                           # from the repo root; docs at http://localhost:8100/scalar
curl -X POST http://localhost:8100/v1/schedule -H "Content-Type: application/json"   -d '{"durationMinutes": 180, "energyKwh": 50, "deadline": "2026-11-03T07:00:00Z"}'
```

| Endpoint | |
|---|---|
| `POST /v1/schedule` | The cleanest window for a job (`durationMinutes`, `energyKwh`, `deadline`, optional `earliestStart`, `region`, `source`), the run-now window, and the CO2 saved with its range |
| `GET /v1/carbon/{region}?source=&from=&to=` | The forecast, half-hour by half-hour (default: NESO's, the next 48 hours) |
| `GET /v1/regions` | GB, its three nations and 14 regions |
| `GET /health`, `GET /ready` | Liveness (checks nothing else), and readiness (below) |

- **Errors are RFC 7807 problem details.**
  - **400** names each invalid field. Timestamps need a zone (`Z` or `+01:00`): .NET would otherwise read
    `2030-01-01T18:00` as the server's local time, ambiguous on a UK clock-change day.
  - **422** means the job can't be scheduled, with the reason (`does-not-fit`, `no-forecast-coverage`).
  - **503** with `Retry-After` means forecasts are unreachable after the client's retries.
- **Readiness separates hard and soft dependencies.** The database down makes it unready (503). The forecasting API
  down only makes it *degraded* (still 200), because NESO-based scheduling still works, so an orchestrator keeps it in
  service.
- **The image** ([Dockerfile](Dockerfile), target `api`) is built from the locked packages and runs on the chiseled
  ASP.NET image: no shell, non-root (uid 1654), 191 MB.
