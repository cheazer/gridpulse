# 0013. Scheduler design: .NET Minimal APIs, forecasts over HTTP, a separate Blazor dashboard

- **Status:** Accepted
- **Date:** 2026-11-02

## Context

Phase 3 turns forecasts into a decision: "I have a 3-hour, 50 kWh job that must finish by Friday 09:00. When should
it run, and how much CO₂ does that save?" It needs GridPulse's national carbon forecasts (with P10/P50/P90, served
by the forecasting API, ADR-0011), NESO's regional forecasts (ingested in Phase 1, in TimescaleDB, not served by any
API), and a dashboard. ADR-0003 chose .NET 10 for this service.

## Options considered

1. **API style:** MVC controllers, or **Minimal APIs**. Minimal APIs are the current .NET default, with less
   ceremony, and they pair with .NET 10's built-in OpenAPI document generation.
2. **Getting the data:** read every table directly from TimescaleDB, or **call the forecasting API for GridPulse's
   forecasts and read NESO's regional forecasts from the database**. The forecasting service owns
   `model_forecast`, and its API is the contract; reading the table directly would couple the scheduler to another
   service's storage. NESO's regional forecasts are raw ingested data that no service exposes, so reading them
   (through a read-only login) is the honest route until one does.
3. **Database access:** Entity Framework Core, or **Dapper**. The queries are read-only, time-series and
   SQL-shaped; Dapper keeps the SQL visible and adds nothing to track.
4. **Dashboard:** Blazor WebAssembly, or **Blazor Interactive Server, as its own app** calling the scheduling API.
   Interactive Server avoids shipping .NET to the browser and needs no cross-origin setup; a separate app uses the
   API like any client and can be deployed on its own. Charts: ApexCharts, for shaded uncertainty bands.
5. **Testing:** xUnit with FluentAssertions, or **xUnit with its own assertions**. FluentAssertions 8 needs a paid
   licence for commercial use. Testcontainers (as in the Java and Python services), WebApplicationFactory for the
   API in memory, bUnit for Blazor components.

## Decision

The options in bold. The solution (`services/scheduler`) has a pure **Core** library (the scheduling logic, no I/O,
exhaustively testable), the **Api** and the **Dashboard**, each with a test project. Across the solution:

- Warnings are errors, with the `latest-recommended` analysers and nullable reference types; `dotnet format
  --verify-no-changes` gates CI.
- Central package management (`Directory.Packages.props`) sets every NuGet version once; `packages.lock.json` files
  pin transitive ones, and CI restores in locked mode.
- The SDK is pinned in `global.json`, which also opts into Microsoft.Testing.Platform: the .NET 10 SDK no longer
  runs xUnit v3 through the old VSTest bridge.
- Time is UTC on the half-hour settlement grid; the UK clock only appears at the edges, for people.

## Consequences

- Calls to the forecasting API need timeouts, retries and a circuit breaker (step 3.3), and the scheduler must
  handle it being down or stale (its `stale` flag).
- Reading NESO's regional forecasts directly couples the scheduler to that table's shape; if Phase 4 or 5 adds an
  API for regional data, the scheduler should switch to it.
- Two deployables (API, dashboard) rather than one, in exchange for a clean client boundary.
