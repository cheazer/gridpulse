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

## Running locally

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/cheazer/gridpulse?quickstart=1)

The quickest route is a Codespace: the dev container ([.devcontainer/](.devcontainer/devcontainer.json)) ships
Java 21, .NET 10, Python 3.12, uv and Docker, and creates `.env` for you. Run `docker compose up -d` in its terminal.

Locally, you need Docker Desktop (or Docker Engine with Compose v2.20+).

```bash
cp .env.example .env    # then set POSTGRES_PASSWORD
docker compose up -d
```

| What | Where |
|---|---|
| Redpanda Console (topics, messages, schemas) | http://localhost:8080 |
| Kafka API (from the host) | `localhost:19092` |
| Schema Registry | http://localhost:18081 |
| TimescaleDB | `localhost:5432` (credentials from `.env`) |

On start-up three one-shot jobs run: `redpanda-init` creates the topics and their dead-letter topics,
`schema-init` registers the event schemas with `BACKWARD` compatibility, and `migrate` applies the
TimescaleDB migrations in [db/migrations](db/migrations) with Flyway. All three are idempotent.

Then `ingestion-app` polls the live Carbon Intensity API (on start-up and every half-hour) and `timescale-sink`
writes the events to TimescaleDB. The first build of the two Java images takes a few minutes. A minute after
start-up, the next half-hour's forecast for every region is in the database:

```bash
docker compose exec timescaledb psql -U gridpulse -d gridpulse -c \
  "SELECT region, forecast_gco2_per_kwh, intensity_index FROM grid.carbon_intensity
   WHERE issued_at IS NOT NULL AND valid_from = (SELECT min(valid_from) FROM grid.carbon_intensity WHERE issued_at IS NOT NULL)
   ORDER BY 2;"
```
`docker compose down -v` stops everything and deletes the data volumes.

To check the whole stack end to end (topics, schemas, a Kafka round trip, TimescaleDB):

```bash
bash deploy/compose/scripts/smoke-test.sh
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
