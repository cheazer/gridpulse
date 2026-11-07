# Forecasting service

Python 3.12 service that forecasts GB national demand and carbon intensity 24–48 hours ahead, benchmarked against
NESO's day-ahead forecast ([ADR-0010](../../docs/adr/0010-day-ahead-benchmark.md)). Design:
[ADR-0011](../../docs/adr/0011-forecasting-design.md).

Phase 2 builds it in steps; so far it holds configuration, read-only database access, the as-of dataset builder, the backtest harness, demand and carbon-intensity models, the forecast job and the API.

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
| `FORECAST_JOB_DB_USER` | `forecast_job` | the forecast job's login: reads, and writes `model_forecast` only |
| `FORECAST_JOB_DB_PASSWORD` | none: required by `forecast` | |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset: no telemetry | the OpenTelemetry collector (OTLP/HTTP, e.g. `http://otel-collector:4318`); see [Telemetry](#telemetry) |

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
`compare-variants`; each is logged to MLflow with `stage=validation`.

Phase 5's four model studies (a learning curve, a short-range input, a "NESO + correction" model and a neural
network) run with `studies` ([studies.py](src/gridpulse_forecasting/studies.py)). It writes
[docs/results/model-studies.md](../../docs/results/model-studies.md); `--cache DIR` keeps the built examples for a
re-run, and `--only` picks studies. The decisions they led to are in ADR-0016. NESO + correction beats NESO (2.73%
against 2.85% MAPE), and LightGBM beats the neural network.

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

## The forecast job

```bash
uv run --env-file ../../.env python -m gridpulse_forecasting train --task all      # weekly: retrain, gate, register
uv run --env-file ../../.env python -m gridpulse_forecasting forecast --task all   # often: champion -> 48 hours
```

[job.py](src/gridpulse_forecasting/job.py) loads each task's champion from the MLflow registry, forecasts
the next 48 hours (96 half-hours, P10/P50/P90) from the latest half-hour, and upserts them into
`grid.model_forecast` (migration V7). The API (step 2.6b) reads `grid.model_forecast_latest`: the newest forecast
for each half-hour. Re-running for the same issue time replaces that forecast instead of duplicating it.

- **Rolling, not just day-ahead.** The scheduler needs the next 48 hours at any time, so the production model trains
  on rolling examples: issued every 6 hours on the UK clock (01:00, 07:00, 13:00, 19:00, so the 13:00 day-ahead case
  is in there), each covering the next 48 hours, with lead time as a feature. The rolling backtest
  (`backtest --mode rolling`) scores exactly this at every lead time.
- **Least privilege.** The job logs in as `forecast_job`: `grid_reader` plus V7's `forecast_writer`, which may write
  `model_forecast` and nothing else. The service's `forecasting` login stays read-only, and the ingestion writer
  (`grid_writer`) has had its default rights on `model_forecast` revoked.
- **Scored at every lead time** ([demand](../../docs/results/forecast-accuracy-rolling.md),
  [carbon](../../docs/results/carbon-accuracy-rolling.md), Oct 2025 to Sep 2026, about 140,000 forecasts each):

  | | Production model, leads 0-48 h | Day-ahead model | Benchmarks |
  |---|---|---|---|
  | Demand MAPE | 4.37%, P10-P90 coverage 78% | 4.23% | NESO day-ahead 2.85%, seasonal naive 8.36% |
  | Carbon MAE | 23.6 g/kWh, coverage 79% | 23.2 g/kWh | NESO final 9.9, seasonal naive 54.3 |

  **Known limitation:** demand error is flat across lead times (4.35% at 0-6 hours, 4.40% at 36-48), so the model
  doesn't yet use fresh data to do better at short range. Its only recent-demand inputs are the latest value and a
  24-hour mean, neither aligned with the target's time of day. Carbon does improve at short range (21.7 g/kWh at
  0-6 hours, 24.4 at 36-48), helped by its latest value. A "latest demand against the same time last week" feature,
  chosen on the validation window, is a Phase 5 item.
- **Cost.** Seconds: it loads the champion and predicts, reading only the two weeks of history the features need.
  Without a champion it stops and says to run `train` first, rather than training silently.

## Retraining and the model registry

`train` ([training.py](src/gridpulse_forecasting/training.py), [ADR-0012](../../docs/adr/0012-model-promotion.md))
retrains each task and decides whether the result replaces the champion (`models:/gridpulse-{task}@champion` in
MLflow):

- **Champion vs challenger on data neither has seen.** The champion was trained up to its cutoff C. The challenger
  (the current code) is trained on exactly that data, and both are scored on everything issued since C. The only
  difference is the code, so a regression shows up; unchanged code ties.
- **Gates:** no more than 1% worse than the champion, MAE at most 75% of seasonal naive's, and P10-P90 coverage
  between 65% and 95%. With no champion, or under a week since its cutoff, only the absolute gates apply, on a
  held-out last two weeks.
- **What's promoted** is a model refit on all data up to now. Every version is registered either way, tagged with
  its training cutoff, git commit, scores and the gate's reasons. `--promote always|never` overrides the gate.
- A retrain that fails its gate exits non-zero, so a scheduled run shows up as failed.
- To override the gate for one registered version, with the reason recorded on it in the registry:
  `promote --task demand --version 1 --reason "..."`. Lowering a threshold after seeing a result isn't an option.


## The API

```bash
docker compose up -d forecasting-api                      # from the repo root; docs at http://localhost:8000/docs
docker compose --profile jobs run --rm forecast-job       # fill it: train, forecast 48 hours, store
curl "http://localhost:8000/v1/forecasts/carbon?from=2026-10-31T00:00Z&to=2026-11-01T00:00Z"
```

[api.py](src/gridpulse_forecasting/api.py), FastAPI:

| Endpoint | |
|---|---|
| `GET /v1/forecasts/{demand\|carbon}?from=&to=` | The newest stored forecast for each half-hour in `[from, to)`: P10/P50/P90, when it was issued, by which model version. Defaults to the next 48 hours; at most 7 days; timestamps must carry a zone. `stale` is true if the newest forecast is over 7 hours old or there is none. |
| `GET /health` | Liveness: the process is up. Never touches the database, so a database outage doesn't get the API restarted. |
| `GET /ready` | Readiness: the database answers (503 if not), so traffic only goes to instances that can serve it. |

- **Nothing is computed per request.** The API reads `grid.model_forecast_latest`, which the forecast job fills, so it
  stays fast and every caller sees the same numbers.
- **Read-only twice over:** it connects as the `forecasting` login (`grid_reader`), and every pooled session starts
  read-only.
- **Two images from one [Dockerfile](Dockerfile).** Both are multi-stage, install dependencies from `uv.lock` only
  (`uv sync --locked`), run as a non-root user, and have the git commit baked in as `GRIDPULSE_VERSION`, which every
  stored forecast records.
  - `api` (252 MB): only what serving needs.
  - `jobs` (1 GB, mostly LightGBM, SciPy and pandas): the forecast and train jobs.

  The modelling libraries are the `jobs` dependency group in [pyproject.toml](pyproject.toml). It's installed by
  default, so `uv sync` and the tests are unchanged, and only the API's image leaves it out. A test checks that
  importing the API loads none of them.
- CI builds both images, starts the API through Compose against a freshly migrated database and smoke-tests it, and
  checks the jobs image can import its modelling stack and the API's can't.

## Telemetry

The API and the forecast job send traces and metrics with [OpenTelemetry](https://opentelemetry.io/docs/languages/python/)
over OTLP/HTTP ([telemetry.py](src/gridpulse_forecasting/telemetry.py)) when `OTEL_EXPORTER_OTLP_ENDPOINT` is set.
Unset, nothing is configured, and tests and local runs stay quiet.

- **The API**:
  - **Traces:** a span per request (FastAPI) and per query (psycopg). Requests from the scheduler carry a W3C
    `traceparent`, so one trace runs from the scheduler's `POST /v1/schedule` through this API to the database.
  - **Left out of traces:** health probes and their database checks, which Compose and Kubernetes run every few
    seconds.
  - **Metrics:** the standard `http.server.request.duration`. It uses the stable semantic conventions, so it has the
    same name and unit (seconds) as in the .NET and Java services.
- **`gridpulse.forecast.age`** (seconds, by `task`): how long ago each task's newest forecast was issued.
  - **When it's read:** from the database at each export, so it keeps growing while nothing new arrives.
  - **What it's for:** it is the metric to alert on. A job that fails, isn't scheduled, or can't reach the model
    registry all show up the same way: forecasts getting old.
- **The forecast job**: a trace per run, with a span per task.
  - Each task's span records the model version and the half-hours stored, plus its queries and the insert.
  - The job sends no metrics of its own: a job that never runs can't report that it didn't. The API's forecast age
    catches that case.
  - Spans are flushed before the process exits, or a short-lived job would lose them.
- **Logs**: the API (uvicorn's own lines included) and the forecast job log to stdout as ECS JSON lines
  (`EcsFormatter`), the same shape as the Java and .NET services write. A line logged inside a span carries its
  `trace.id` and `span.id`. The other commands are run by a person, and print readable reports instead.
- Tests read the telemetry back through in-memory exporters (`tests/test_telemetry.py`), as a collector would
  receive it.
