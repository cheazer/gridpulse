# Scheduler service

C# / .NET 10. Finds the lowest-carbon window for flexible electricity use in Great Britain, and shows it on a
dashboard. Design: [ADR-0013](../../docs/adr/0013-scheduler-design.md).

Phase 3 builds it in steps; so far it holds the solution skeleton and the half-hour time grid.

| Project | |
|---|---|
| `src/GridPulse.Scheduler.Core` | The scheduling logic: pure functions over forecasts, no I/O |
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
