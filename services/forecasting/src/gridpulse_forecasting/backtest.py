"""Rolling-origin backtest: refit monthly on what was known, forecast the month, compare on the same half-hours.

For each test month the model is refitted at the month's first issue time, on examples whose target had been
published by then, and then forecasts every day of the month as of 13:00 the day before. That is how it would
have run live, so the error is what it would have been live (ADR-0011).
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

import pandas as pd

from gridpulse_forecasting.features import FEATURES, LONDON
from gridpulse_forecasting.metrics import interval_coverage, pinball_loss, point_metrics
from gridpulse_forecasting.models import Forecaster
from gridpulse_forecasting.sources import INDO_PUBLICATION_LAG

HALF_HOUR = pd.Timedelta(minutes=30)
QUANTILES = {"p10": 0.1, "p50": 0.5, "p90": 0.9}

# Column names for each forecast in the predictions table.
MODEL, NESO, NAIVE = "model", "neso", "seasonal_naive"


@dataclass(frozen=True)
class BacktestResult:
    # One row per target half-hour: fold, issue_time, actual, model_p50 (p10, p90), neso, seasonal_naive.
    predictions: pd.DataFrame
    # One row per forecast (model, neso, seasonal_naive): metrics on the half-hours all three cover.
    summary: pd.DataFrame
    # MAPE per test month and forecast, on the same half-hours.
    by_month: pd.DataFrame
    # The last month's model, fitted on the most history: what feature importance is read from.
    final_model: Forecaster


def backtest_months(examples: pd.DataFrame, months: int) -> list[pd.Period]:
    """The last ``months`` complete UK months with known targets."""
    # Distinct half-hours, not rows: a rolling dataset forecasts each half-hour several times, so counting rows would
    # call a half-finished month complete.
    known = examples.loc[examples["target"].notna(), "target_time"].drop_duplicates()
    per_month = uk_month(known).value_counts()
    # Complete means at least 46 half-hours (the shortest UK day) for every day of the month.
    complete = [
        month
        for month, half_hours in zip(map(pd.Period, per_month.index), per_month, strict=True)
        if half_hours >= month.days_in_month * 46
    ]
    return sorted(complete)[-months:]


def uk_month(times: pd.Series) -> pd.Series:
    """The UK calendar month of each UTC time (a period carries no zone, so convert to wall time first)."""
    months: pd.Series = times.dt.tz_convert(LONDON).dt.tz_localize(None).dt.to_period("M")
    return months


def training_rows(examples: pd.DataFrame, refit_time: pd.Timestamp) -> pd.DataFrame:
    """Examples whose actual had been published by ``refit_time``: the model can't learn from the future either."""
    published = examples["target_time"] + HALF_HOUR + INDO_PUBLICATION_LAG <= refit_time
    return examples[published & examples["target"].notna()]


def run_backtest(
    new_model: Callable[[], Forecaster],
    examples: pd.DataFrame,
    neso: pd.Series,
    months: int = 12,
    naive_feature: str = "demand_lag_7d",
) -> BacktestResult:
    """Backtest a fresh model per month over the last ``months`` complete months of ``examples``."""
    folds = []
    model: Forecaster | None = None
    local_month = uk_month(examples["target_time"])
    for month in backtest_months(examples, months):
        test = examples[(local_month == month) & examples["target"].notna()]
        refit_time = test["issue_time"].min()
        model = new_model()
        train = training_rows(examples, refit_time)
        model.fit(train[FEATURES], train["target"])
        forecast = model.predict(test[FEATURES]).add_prefix(f"{MODEL}_")
        folds.append(
            pd.concat(
                [
                    # Seasonal naive: the target a week earlier at the same UK clock time, already a feature of
                    # each example. Taken row by row: a rolling dataset forecasts each half-hour several times.
                    test[["target_time", "issue_time"]].assign(
                        fold=str(month), actual=test["target"], **{NAIVE: test[naive_feature]}
                    ),
                    forecast,
                ],
                axis=1,
            )
        )
    if model is None:
        raise ValueError(f"no complete months with known targets to test on (asked for {months})")
    predictions = pd.concat(folds, ignore_index=True)
    predictions[NESO] = neso.reindex(predictions["target_time"]).to_numpy()
    return BacktestResult(predictions, *score(predictions), final_model=model)


def score(predictions: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Metrics per forecast, only on half-hours where every forecast and the actual exist."""
    point = {MODEL: f"{MODEL}_p50", NESO: NESO, NAIVE: NAIVE}
    common = predictions.dropna(subset=["actual", *point.values()])
    if common.empty:
        raise ValueError("no half-hours where the model, NESO and seasonal naive all have forecasts")

    rows = {}
    for name, column in point.items():
        rows[name] = {"half_hours": len(common), **point_metrics(common["actual"], common[column])}
    if {f"{MODEL}_p10", f"{MODEL}_p90"} <= set(common.columns):
        for label, quantile in QUANTILES.items():
            rows[MODEL][f"pinball_{label}"] = pinball_loss(common["actual"], common[f"{MODEL}_{label}"], quantile)
        rows[MODEL]["coverage_p10_p90"] = interval_coverage(
            common["actual"], common[f"{MODEL}_p10"], common[f"{MODEL}_p90"]
        )
    summary = pd.DataFrame.from_dict(rows, orient="index")

    by_month = pd.DataFrame(
        {
            name: {
                fold: point_metrics(month["actual"], month[column])["mape_pct"]
                for fold, month in common.groupby("fold")
            }
            for name, column in point.items()
        }
    )
    return summary, by_month
