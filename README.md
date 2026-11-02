# GridPulse

Real-time UK grid intelligence: ingest live grid data, forecast demand and carbon intensity 24–48 h ahead,
and expose a carbon-aware scheduling API that finds the lowest-carbon window for energy-heavy workloads.

> **Status:** Phases 1 and 2 complete: live ingestion from three sources with two years of history, and
> day-ahead and 48-hour forecasts of demand and carbon intensity, backtested against NESO, stored by a scheduled
> job, served by an API and retrained behind a champion/challenger gate. Next: Phase 3, the carbon-aware
> scheduling API and dashboard. This README grows into the full write-up in Phase 5.

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
docs/results/        Data-quality, forecast-accuracy and experiment reports (generated, committed)
```

## Running locally

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/cheazer/gridpulse?quickstart=1)

The quickest route is a Codespace: the dev container ([.devcontainer/](.devcontainer/devcontainer.json)) ships
Java 21, .NET 10, Python 3.12, uv and Docker, and creates `.env` for you. Run `docker compose up -d` in its terminal.

Locally, you need Docker Desktop (or Docker Engine with Compose v2.20+).

```bash
cp .env.example .env    # then set the four *_PASSWORD values
docker compose up -d
```

| What | Where |
|---|---|
| Redpanda Console (topics, messages, schemas) | http://localhost:8080 |
| Kafka API (from the host) | `localhost:19092` |
| Schema Registry | http://localhost:18081 |
| TimescaleDB | `localhost:5432` (credentials from `.env`) |
| MLflow (experiments, model registry) | http://localhost:5000 |
| Forecasting API (OpenAPI docs) | http://localhost:8000/docs |

On start-up four one-shot jobs run: `redpanda-init` creates the topics and their dead-letter topics,
`schema-init` registers the event schemas with `BACKWARD` compatibility, `migrate` applies the
TimescaleDB migrations in [db/migrations](db/migrations) with Flyway, and `db-users` creates per-service logins
(the forecasting service's read-only one, the forecast job's, which may also write model forecasts, and MLflow's,
which owns its own `mlflow` database) with passwords from
`.env`. All four are idempotent.

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

### Loading history

Live polling only looks back hours. To load two years of history through the same pipeline
([ADR-0009](docs/adr/0009-backfill.md)):

```bash
docker compose --profile backfill run --rm backfill
```

It works through each dataset in chunks sized to the source's limits, records a checkpoint after each chunk,
and resumes where it stopped if interrupted. Narrow it with `-e GRIDPULSE_BACKFILL_JOBS=elexon-ndf` or
`-e GRIDPULSE_BACKFILL_FROM=2025-01-01`.

Measured on a 2-core Codespace (29 Oct 2026), two years from 29 Oct 2024:

| | |
|---|---|
| Chunks / events | 1,678 chunks, 2.61 million events (0 dead-lettered) |
| Time | about 9 minutes, plus a 69-second resume after two jobs were interrupted |
| Stored | 3.3 million rows, 843 MB before compression |

| Table | Rows | Notes |
|---|---|---|
| `demand_forecast` | 2,054,592 | every NESO NDF revision: the Phase 2 benchmark |
| `generation_mix` | 769,436 | FUELHH by fuel code, plus NESO embedded solar and wind |
| `weather` | 420,960 | 8 locations hourly: reanalysis, and archived forecasts made ~1 and ~2 days ahead |
| `demand` | 138,284 | Elexon INDO/ITSDO and NESO settled ND/TSD |
| `carbon_intensity` | 38,457 | national actuals and forecasts |
| `system_price` | 35,063 | imbalance prices |

### Data quality

```bash
uv run tools/data-quality/report.py --output docs/results/data-quality.md
```

Checks coverage and gaps per series, duplicates, clock-change days, value ranges and agreement between sources,
and computes the day-ahead benchmark ([ADR-0010](docs/adr/0010-day-ahead-benchmark.md)). The
[latest report](docs/results/data-quality.md), on the two-year load: every half-hourly series 100% complete,
0 duplicated records, all four clock-change days correct, and Elexon's initial demand within 0.23% of NESO's
settled figure.

**The number to beat in Phase 2:** NESO's day-ahead demand forecast, as published by 13:00 the day before, has
a **MAPE of 2.74%** (MAE 685 MW) over 34,737 half-hours, against 8.45% for a seasonal-naive forecast.

## Forecasting results

Rolling-origin backtests over **Oct 2025 to Sep 2026**: a fresh model each month, trained only on what was known
at the time, forecasting each day as of 13:00 UK time the day before (NESO's own cut-off). Every forecast is
scored on the same half-hours. Features are built strictly as of the issue time, and leakage tests prove it
([ADR-0010](docs/adr/0010-day-ahead-benchmark.md), [ADR-0011](docs/adr/0011-forecasting-design.md)).

| Day-ahead | GridPulse | NESO | Seasonal naive | P10-P90 coverage |
|---|---:|---:|---:|---:|
| National demand, MAPE ([report](docs/results/forecast-accuracy.md)) | 4.23% | **2.85%** | 8.36% | 79.7% |
| Carbon intensity, MAE ([report](docs/results/carbon-accuracy.md)) | 23.2 g/kWh | 9.9 g/kWh* | 54.3 g/kWh | 80% |

\* NESO's *final* carbon forecast, made shortly before each half-hour: the only NESO forecast recorded for this
period, so a ceiling rather than a like-for-like comparison.

- **The demand model does not beat NESO yet.** It halves seasonal naive's error. Ten variants compared on a
  separate validation window (per-location solar, a ratio target, week-on-week features, hyperparameters) found
  nothing better, and the test months were scored only once
  ([experiments](docs/results/demand-model-experiments.md)). The gap is largest at midday, when embedded solar cuts
  demand; NESO forecasts it directly. More history and a "NESO + correction" model are Phase 5 work.
- **The intervals are honest.** Plain quantile models covered only 48% of actuals out of sample; conformalised
  quantile regression brings the P10-P90 band to about 80%.
- **What is served** is a rolling model covering every lead time from 0 to 48 hours. It scores about the same
  (demand 4.37%, carbon 23.6 g/kWh: [demand](docs/results/forecast-accuracy-rolling.md),
  [carbon](docs/results/carbon-accuracy-rolling.md)). Demand error doesn't fall at short leads yet, a known
  limitation.

### Running the forecasts

```bash
docker compose up -d                                      # includes MLflow (:5000) and the forecasting API (:8000)
docker compose --profile jobs run --rm train-job          # retrain, gate against the champion, register
docker compose --profile jobs run --rm forecast-job       # champion -> next 48 hours -> grid.model_forecast
curl http://localhost:8000/v1/forecasts/carbon            # P10/P50/P90 per half-hour; docs at /docs
```

A retrained model becomes champion only if it is no worse than the current one on data neither has seen, beats
seasonal naive clearly and has calibrated intervals; overrides are recorded with a reason
([ADR-0012](docs/adr/0012-model-promotion.md)). Details: [services/forecasting](services/forecasting/README.md).

## Data sources

| Source | Used for | Licence |
|---|---|---|
| [Carbon Intensity API](https://carbonintensity.org.uk/) (NESO) | National & regional carbon intensity, generation mix, NESO's CI forecast | CC BY 4.0 |
| [Elexon Insights Solution](https://bmrs.elexon.co.uk/api-documentation) | Demand outturn, generation by fuel, system prices, day-ahead demand forecast | BMRS data licence |
| [NESO Data Portal](https://www.neso.energy/data-portal) | Historical demand | NESO Open Data Licence |
| [Open-Meteo](https://open-meteo.com/) | Historical & forecast weather | CC BY 4.0 (non-commercial use) |

## Decisions

Twelve architecture decision records, indexed in [docs/adr](docs/adr/README.md). The ones that shape the results:

- [0004](docs/adr/0004-event-identity-and-time.md): deterministic event IDs and UTC timestamps, so replays and
  backfills are idempotent.
- [0010](docs/adr/0010-day-ahead-benchmark.md): the benchmark is NESO's forecast as published by 13:00 the day
  before, and our model gets the same information cut-off.
- [0011](docs/adr/0011-forecasting-design.md): as-of features, one LightGBM model across horizons, conformal
  intervals, forecasts served from a table.
- [0012](docs/adr/0012-model-promotion.md): a new model replaces the champion only if it is no worse on data
  neither has seen.
