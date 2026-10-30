# Forecasting service

Python 3.12 service that forecasts GB national demand and carbon intensity 24–48 hours ahead, benchmarked against
NESO's day-ahead forecast ([ADR-0010](../../docs/adr/0010-day-ahead-benchmark.md)). Design:
[ADR-0011](../../docs/adr/0011-forecasting-design.md).

Phase 2 builds it in steps; so far it holds configuration, read-only database access, the as-of dataset builder, the backtest harness, and day-ahead models for demand and carbon intensity.

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
uv run --env-file ../../.env python -m gridpulse_forecasting backtest --task demand --model seasonal-naive
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

## The demand model

`lightgbm-cqr` ([models.py](src/gridpulse_forecasting/models.py)): LightGBM with the quantile (pinball) objective, one
model each for P10, P50 and P90, so the band is learned rather than a symmetric error bar. Hyperparameters were set
once before any backtest and not tuned on the test months.

**Calibrated intervals.** Plain quantile models were badly overconfident on new data: their P10-P90 band held only
48% of actuals instead of 80%, because they partly fit the training noise and demand drifts. Conformalised quantile
regression (CQR) fixes this. The band models are fitted without the last 8 weeks of training data, and those 8 weeks
measure how far actuals fell outside the band. The band is then widened by that margin, the ceil((n + 1) x 0.8)-th
smallest overshoot. The median model, which needs no calibration, still trains on everything.

**Results** ([docs/results/forecast-accuracy.md](../../docs/results/forecast-accuracy.md), Oct 2025 to Sep 2026):

| | MAPE | P10-P90 coverage |
|---|---|---|
| GridPulse (`lightgbm-cqr`) | 4.23% | 79.7% (plain quantiles: 48%) |
| NESO day-ahead | **2.85%** | - |
| Seasonal naive | 8.36% | - |

The model halves seasonal naive's error but does **not** beat NESO yet; it trails in every month. The gap is largest
at midday (12:00-16:00 UK: 6.6% against NESO's 4.4%), when embedded solar, invisible to the transmission system,
cuts national demand. NESO forecasts embedded solar and wind directly; this model only sees national-average
radiation, and leans mostly on past demand.

**Trying to close the gap** ([docs/results/demand-model-experiments.md](../../docs/results/demand-model-experiments.md)).
Ten variants were compared on a validation window (Jul to Sep 2025) before the test months, so the test months were
scored only once: per-location solar, a ratio-to-last-week target, week-on-week changes in demand and weather, and
four hyperparameter settings. None beat the first model by more than noise, so it stays. Run them with
`compare-variants`; each is logged to MLflow with `stage=validation`. More history and a "NESO + correction" model
are left for Phase 5.

## The carbon-intensity model

`backtest --task carbon --model lightgbm-cqr`: the same pipeline, model and calibration, for national (GB) carbon
intensity in gCO2/kWh. A task ([tasks.py](src/gridpulse_forecasting/tasks.py)) bundles what differs: the target,
unit, benchmark, seasonal-naive lag and MLflow experiment (`day-ahead-carbon`). Inputs are the demand model's plus
carbon intensity's own history (published an hour after each half-hour, like INDO), set once and not tuned.

**Results** ([docs/results/carbon-accuracy.md](../../docs/results/carbon-accuracy.md), Oct 2025 to Sep 2026):

| | MAE | MAPE | P10-P90 coverage |
|---|---|---|---|
| GridPulse day-ahead | 23.2 g/kWh | 21.8% | 80% |
| NESO *final* forecast (not day-ahead) | 9.9 g/kWh | 9.4% | - |
| Seasonal naive | 54.3 g/kWh | 53.6% | - |

- **The NESO number is a ceiling, not a rival.** The history holds only NESO's final forecast for each half-hour,
  made shortly before it; its day-ahead revisions are recorded only from late October 2026. Report and PR say so.
- **MAE leads, not MAPE.** Carbon intensity can fall towards zero, where percentage errors explode. One zero actual
  (6 Aug 2026, physically implausible for GB) is treated as missing.
- **Wind drives it**: 100 m wind speed is the model's largest input (27% of split gain), because wind output decides
  how much gas must run.
