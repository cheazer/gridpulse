# Forecasting service

Python 3.12 service that forecasts GB national demand and carbon intensity 24–48 hours ahead, benchmarked against
NESO's day-ahead forecast ([ADR-0010](../../docs/adr/0010-day-ahead-benchmark.md)). Design:
[ADR-0011](../../docs/adr/0011-forecasting-design.md).

Phase 2 builds it in steps; so far it holds configuration, read-only database access, the as-of dataset builder and the backtest harness.

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

## The dataset: features as of the issue time

`build-dataset` makes one row per (issue time, target half-hour): features as they could have been computed at
13:00 UK time the day before (ADR-0010), and the actual demand as the label.

```bash
uv run --env-file ../../.env python -m gridpulse_forecasting build-dataset --from 2024-11-13 --to 2026-10-29 --output /tmp/day-ahead.parquet
```

- **Every input row says when it became known** (`available_at`): INDO an hour after its period ends (a
  deliberately pessimistic allowance), weather forecasts at their issue time. `Sources.as_of(t)` drops everything
  later, and the feature code only ever receives that snapshot ([sources.py](src/gridpulse_forecasting/sources.py)).
- **Leakage tests** ([test_leakage.py](tests/test_leakage.py)) scramble everything published after the issue time
  and require identical features, with a control test showing the scrambling does change features when it
  reaches earlier data.
- **Features** ([features.py](src/gridpulse_forecasting/features.py)): lead time; UK-local calendar (half-hour of
  day, weekday, England and Wales bank holidays, Christmas period, season); demand at the same UK clock time 2, 7
  and 14 days earlier, the latest published value and the 24-hour mean; population-weighted temperature, heating
  degrees, wind, solar radiation (shifted: Open-Meteo stamps it at the end of its hour), cloud, and how old the
  weather forecast was. Missing inputs stay NaN, never filled with a guess; LightGBM handles NaN natively.

## Backtesting

```bash
uv run --env-file ../../.env python -m gridpulse_forecasting backtest --model seasonal-naive
```

A rolling-origin backtest ([backtest.py](src/gridpulse_forecasting/backtest.py)) over the last 12 complete months:

- **Refit monthly, on what was known.** Each month gets a fresh model, fitted at the month's first issue time on
  every earlier example whose actual had been published by then (an expanding window). It then forecasts each day
  of the month as of 13:00 the day before, as it would have live.
- **One yardstick.** The model, NESO's day-ahead forecast and seasonal naive are scored on exactly the same
  half-hours: MAE, RMSE, MAPE and bias; for quantile models also pinball loss per quantile and P10-P90 coverage
  ([metrics.py](src/gridpulse_forecasting/metrics.py)). No overlap is an error, never an empty score.
- **Tracked.** With `MLFLOW_TRACKING_URI` set (it is in `.env.example`), each backtest is an MLflow run in the
  `day-ahead-demand` experiment: parameters, every forecast's metrics, the git commit, and the summary and monthly
  tables as artifacts. Without it the backtest still runs and logs nothing.

Seasonal naive over Oct 2025 to Sep 2026: **8.36% MAPE** against NESO's **2.85%** on the same 17,520 half-hours.
NESO's 2.85% is higher than the two-year 2.74% in the data-quality report because these 12 months include its
weakest stretch, March to July (3.2-4.0% a month).
