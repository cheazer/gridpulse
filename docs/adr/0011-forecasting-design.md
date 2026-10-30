# 0011. Forecasting design: as-of features, one LightGBM model across horizons, forecasts served from a table

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

Phase 2 forecasts GB national demand and carbon intensity 24–48 hours ahead and must show, honestly, how it
compares with NESO's day-ahead demand forecast: 2.74% MAPE at the 13:00 D-1 cut-off (ADR-0010). Phase 3's
carbon-aware scheduler will consume the forecasts. We have two years of half-hourly data (Phase 1), so the data is
small: about 35,000 half-hours per series.

The usual way such projects fool themselves is **leakage**: training on information that wasn't available when the
forecast would have been made. In our data that has three concrete forms:

- **Settled demand arrives weeks late.** NESO's settled ND, the target, isn't known at forecast time; Elexon's
  initial outturn (INDO) is known within minutes and agrees with ND to 0.23% MAPE.
- **Weather "actuals" aren't forecasts.** Reanalysis (ADR-0008) is what happened; at 13:00 D-1 only the weather
  forecast issued by then was known.
- **Forecasts get revised.** Any input that is itself a forecast must be the revision published by the cut-off.

## Options considered

1. **Dataframes: pandas or polars.** polars is faster, but at 35k rows speed doesn't matter; pandas is what
   LightGBM, MLflow and most readers expect. **pandas** (3.x).
2. **NESO's own forecast as a feature.** Feeding NDF in usually improves accuracy, but then "beats NESO" mostly
   means "corrects NESO". **Excluded from the headline model**; a "NESO + correction" model may be reported
   separately and labelled as such.
3. **One model per horizon, or one model for all.** 96 per-horizon models (or recursive one-step forecasting,
   which compounds its own errors) against **one direct model with lead time as a feature**: one artefact to
   track, and it learns how accuracy decays with lead time. It serves both the 13:00 day-ahead benchmark and a
   rolling 48-hour forecast.
4. **Experiment tracking.** MLflow with local files (lost with the container), or **MLflow server with its
   metadata in a separate `mlflow` database in the existing PostgreSQL** and artefacts on a volume. A model
   registry alias, `champion`, marks the model in use.
5. **Serving.** Compute on each request (slow, results vary between callers), or **a scheduled job writes
   forecasts to `grid.model_forecast`** and the API reads them: fast, cacheable, everyone sees the same numbers,
   and the dashboard can chart model vs NESO vs actual straight from the database.

## Decision

- **As-of datasets.** Every training example is built as it would have looked at its issue time: demand inputs
  from INDO up to the cut-off, the target from `grid.national_demand_actual`, weather from forecasts issued by the
  cut-off, never reanalysis. Leakage tests check this mechanically: features for an issue time must not change
  when data after it is added or altered.
- **Model:** LightGBM, one global direct multi-horizon model per target (demand, national carbon intensity), with
  quantile objectives for P10/P50/P90. Interval coverage is measured; if an 80% interval doesn't hold about 80% of
  actuals, it is calibrated with conformalised quantile regression.
- **Evaluation:** rolling-origin backtest with an expanding window, refitting monthly over the last 12 months.
  MAE, RMSE, MAPE, bias, pinball loss and interval coverage, always on exactly the half-hours where the model, NESO
  and seasonal naive all have values.
- **Database access:** the service logs in as `forecasting`, which only holds `grid_reader`, and sessions start
  read-only. The login is created by the Compose `db-users` job from `FORECAST_DB_PASSWORD`; no password lives in
  a migration. Writing forecasts (step 2.6) gets its own narrowly granted login.
- **Tooling:** Python 3.12 with uv (lockfile checked in, `uv sync --locked` in CI), ruff for formatting and
  linting, mypy in strict mode, pytest with Testcontainers for database tests.

## Consequences

- Results are comparable with NESO's by construction, and the claim "beats NESO's day-ahead forecast" can be made,
  or not, without caveats about information advantage.
- Carbon intensity has a weaker benchmark: before October 2026 we hold only NESO's final forecast for each
  half-hour, not its day-ahead revision, so comparisons over that period favour NESO and are labelled that way.
- Forecasts in a table are as fresh as the last job run; Phase 4 schedules it (CronJob) and alerts if it goes stale.
- Regional carbon intensity is not modelled; Phase 3 uses NESO's regional 48-hour forecasts, which we ingest.
- **Historical weather needs two revisions** (found in step 2.2). The previous-runs archive's ~24-hour-ahead run for
  D's evening hours (18:00-23:00 UTC) is issued after 13:00 on D-1, so under the cut-off it doesn't exist yet. The
  backfill therefore also loads the ~48-hour-ahead run (`open-meteo-two-day-ahead-forecasts`), and the as-of rule
  picks whichever revision was the latest by the cut-off. Those forecasts are older than what NESO had at 13:00,
  which can only work against our model.
