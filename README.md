# GridPulse

Real-time UK grid intelligence: ingest live grid data, forecast demand and carbon intensity 24–48 h ahead,
and expose a carbon-aware scheduling API that finds the lowest-carbon window for energy-heavy workloads.

> **Status:** Phase 1 in progress (ingestion + storage). This README grows into the full write-up in Phase 5.

## Services at a glance

| Service | Language | Purpose |
|---|---|---|
| `services/ingestion` | Java 21, Spring Boot | Poll public APIs, normalise to the shared event schema, publish to Kafka; sink events into TimescaleDB |
| `services/forecasting` | Python 3.12, FastAPI | Feature pipeline, models, backtesting vs NESO, forecast API |
| `services/scheduler` | C# / .NET 10 | Scheduling API (lowest-carbon window, CO₂ saved) and Blazor dashboard |

## Repository layout

```
schemas/events/v1/   Shared, versioned event contracts (JSON Schema) + golden examples
services/            One folder per service
db/migrations/       TimescaleDB schema (Flyway)
deploy/              Docker Compose (local) and Helm (k8s)
observability/       OpenTelemetry collector, Prometheus, Grafana
loadtest/k6/         Load tests for the scheduling API
docs/adr/            Architecture decision records
```

## Data sources

| Source | Used for | Licence |
|---|---|---|
| [Carbon Intensity API](https://carbonintensity.org.uk/) (NESO) | National & regional carbon intensity, generation mix, NESO's CI forecast | CC BY 4.0 |
| [Elexon Insights Solution](https://bmrs.elexon.co.uk/api-documentation) | Demand outturn, generation by fuel, system prices, day-ahead demand forecast | BMRS data licence |
| [NESO Data Portal](https://www.neso.energy/data-portal) | Historical demand | NESO Open Data Licence |
| [Open-Meteo](https://open-meteo.com/) | Historical & forecast weather | CC BY 4.0 (non-commercial use) |

## Decisions

See [docs/adr](docs/adr/README.md).
