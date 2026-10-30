# Forecasting service

Python 3.12 service that forecasts GB national demand and carbon intensity 24–48 hours ahead, benchmarked against
NESO's day-ahead forecast ([ADR-0010](../../docs/adr/0010-day-ahead-benchmark.md)). Design:
[ADR-0011](../../docs/adr/0011-forecasting-design.md).

Phase 2 builds it in steps; so far it holds configuration and read-only database access.

## Develop

Needs [uv](https://docs.astral.sh/uv/); it installs Python 3.12 if missing. From this folder:

```bash
uv sync                                  # creates .venv from uv.lock
uv run ruff format . && uv run ruff check . && uv run mypy
uv run pytest -m "not integration"       # unit tests, no Docker
uv run pytest                            # everything; integration tests start a TimescaleDB container
```

## Configuration

Environment variables only, with the same names as the rest of the stack (`.env.example`):

| Variable | Default | |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | |
| `TIMESCALE_PORT` | `5432` | |
| `POSTGRES_DB` | `gridpulse` | |
| `FORECAST_DB_USER` | `forecasting` | a login holding only `grid_reader`, created by Compose's `db-users` job |
| `FORECAST_DB_PASSWORD` | none: required | |

Against the running stack, pass the repo's `.env`:

```bash
uv run --env-file ../../.env python -m gridpulse_forecasting check-db
```
