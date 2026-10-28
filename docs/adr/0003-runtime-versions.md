# 0003. Runtime versions: Java 21, .NET 10, Python 3.12

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

Each service should run on a version that is supported for the life of the project and familiar to employers.
The original brief specified .NET 8, but .NET 8's support ends on 10 November 2026.

## Decision

| Service | Runtime | Reason |
|---|---|---|
| Ingestion | **Java 21 (LTS)** | Virtual threads (Project Loom) suit I/O-bound pollers; supported by Spring Boot 3.x and 4.x; most widely deployed LTS in industry. |
| Scheduler | **.NET 10 (LTS)** | Current LTS, supported to November 2028. .NET 8 would be out of support within weeks of the project starting. |
| Forecasting | **Python 3.12** | Fully supported by LightGBM, pandas, PyTorch and MLflow wheels. Managed with `uv` so the system Python is untouched. |

Exact framework and library versions are pinned in each service's build file and kept current with Dependabot.

## Consequences

- Java 25 (LTS) is available, but 21 has the broadest library and tooling support; moving to 25 is a later,
  low-risk upgrade.
- Container base images: Eclipse Temurin 21 JRE, `mcr.microsoft.com/dotnet/aspnet:10.0`, `python:3.12-slim`.
