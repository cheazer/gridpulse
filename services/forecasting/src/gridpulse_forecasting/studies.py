"""Phase 5's model studies (ADR-0016): four questions about the forecasts, each answered by the rolling-origin backtest.

1. Learning curve: how much does accuracy improve with more months of training history?
2. Short range: does a "latest demand against a week ago" input make the rolling model better at short leads?
3. NESO + correction: how good is a model that takes NESO's own day-ahead forecast as an input and learns its errors?
4. Neural network: on the same features and folds, does a neural network beat gradient-boosted trees?

Each compares point forecasts (P50) from models refitted month by month exactly as the headline backtest does, so the
numbers are comparable with each other. Study 2 chooses a design, so it runs on the validation months; the others are
measurements, made once on the test months.
"""

from __future__ import annotations

import datetime as dt
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from functools import partial
from itertools import pairwise

import pandas as pd

from gridpulse_forecasting.backtest import MODEL, NESO, BacktestResult, run_backtest
from gridpulse_forecasting.features import BASE_FEATURES, SHORT_RANGE
from gridpulse_forecasting.metrics import point_metrics
from gridpulse_forecasting.models import NESO_FEATURE, Forecaster, LightGBMMedian, ModelConfig, NeuralNetwork
from gridpulse_forecasting.report import table
from gridpulse_forecasting.tasks import CARBON_CONFIG, Task

# Training history for the learning curve, in months. 18 is the most every test month has behind it.
WINDOWS_MONTHS = (3, 6, 9, 12, 18)
LEARNING_CURVE_TEST_MONTHS = 6
TEST_MONTHS = 12
# Lead-time buckets for the short-range study, in hours (lower bound inclusive).
LEAD_BUCKETS = (0, 6, 12, 24, 36, 48)

MONTH = pd.Timedelta(days=30.44)


@dataclass(frozen=True)
class Comparison:
    """Named backtests of the same examples, on the same folds."""

    results: dict[str, BacktestResult]

    def summary(self) -> pd.DataFrame:
        """MAE and MAPE of each model, and NESO's, on the half-hours every one of them covers."""
        common = None
        for result in self.results.values():
            keys = result.predictions.dropna(subset=["actual", f"{MODEL}_p50", NESO])[["target_time", "issue_time"]]
            index = pd.MultiIndex.from_frame(keys)
            common = index if common is None else common.intersection(index)
        assert common is not None
        rows = {}
        for name, result in self.results.items():
            p = result.predictions.set_index(["target_time", "issue_time"]).loc[common]
            rows[name] = point_metrics(p["actual"], p[f"{MODEL}_p50"])
        first = next(iter(self.results.values())).predictions.set_index(["target_time", "issue_time"]).loc[common]
        rows["NESO"] = point_metrics(first["actual"], first[NESO])
        frame = pd.DataFrame.from_dict(rows, orient="index")[["mae", "mape_pct"]]
        frame["half_hours"] = len(common)
        return frame


def learning_curve(task: Task, examples: pd.DataFrame, benchmark: pd.Series) -> Comparison:
    config = CARBON_CONFIG if task.name == "carbon" else ModelConfig()
    return Comparison(
        {
            f"{months} months": run_backtest(
                partial(LightGBMMedian, config),
                examples,
                benchmark,
                LEARNING_CURVE_TEST_MONTHS,
                task.naive_feature,
                train_window=months * MONTH,
            )
            for months in WINDOWS_MONTHS
        }
    )


def short_range(rolling: pd.DataFrame, benchmark: pd.Series, months: int) -> Comparison:
    """The first demand model, with and without SHORT_RANGE, on rolling examples (every lead from 0 to 48 hours)."""
    with_short_range = ModelConfig(features=(*BASE_FEATURES, *SHORT_RANGE))
    return Comparison(
        {
            "without": run_backtest(partial(LightGBMMedian, ModelConfig()), rolling, benchmark, months),
            "with short-range input": run_backtest(
                partial(LightGBMMedian, with_short_range), rolling, benchmark, months
            ),
        }
    )


def by_lead(comparison: Comparison) -> pd.DataFrame:
    """MAE per lead-time bucket and model: where in the 48 hours each one is good."""
    columns = {}
    for name, result in comparison.results.items():
        p = result.predictions.dropna(subset=["actual", f"{MODEL}_p50"])
        lead = (p["target_time"] - p["issue_time"]).dt.total_seconds() / 3600
        bucket = pd.cut(lead, LEAD_BUCKETS, right=False, labels=bucket_labels())
        error = (p["actual"] - p[f"{MODEL}_p50"]).abs()
        columns[name] = error.groupby(bucket, observed=True).mean()
    return pd.DataFrame(columns)


def bucket_labels() -> list[str]:
    return [f"{low}-{high} h" for low, high in pairwise(LEAD_BUCKETS)]


def neso_correction(examples: pd.DataFrame, benchmark: pd.Series, months: int = TEST_MONTHS) -> Comparison:
    """GridPulse's own model against one that also sees NESO's forecast and predicts NESO's error."""
    with_neso = with_neso_forecast(examples, benchmark)
    correction = ModelConfig(features=(*BASE_FEATURES, NESO_FEATURE), target="residual_to_neso")
    return Comparison(
        {
            "GridPulse": run_backtest(partial(LightGBMMedian, ModelConfig()), with_neso, benchmark, months),
            "NESO + correction": run_backtest(
                partial(LightGBMMedian, correction), with_neso, benchmark, months, extra_features=[NESO_FEATURE]
            ),
        }
    )


def with_neso_forecast(examples: pd.DataFrame, benchmark: pd.Series) -> pd.DataFrame:
    """Day-ahead examples with NESO's forecast for each target half-hour as published by 13:00 the day before.

    The benchmark series is exactly that (ADR-0010), so this is information the model would have had at issue time.
    """
    return examples.assign(**{NESO_FEATURE: benchmark.reindex(examples["target_time"]).to_numpy()})


def neural(task: Task, examples: pd.DataFrame, benchmark: pd.Series, months: int = TEST_MONTHS) -> Comparison:
    config = CARBON_CONFIG if task.name == "carbon" else ModelConfig()
    models: dict[str, Callable[[], Forecaster]] = {
        "LightGBM": partial(LightGBMMedian, config),
        "Neural network": partial(NeuralNetwork, config),
    }
    return Comparison(
        {name: run_backtest(new, examples, benchmark, months, task.naive_feature) for name, new in models.items()}
    )


# ---------------------------------------------------------------------------------------------------------- report


def folds_of(comparison: Comparison) -> str:
    folds = sorted(next(iter(comparison.results.values())).predictions["fold"].unique())
    return f"{folds[0]} to {folds[-1]}, {len(folds)} months"


def summary_table(comparison: Comparison, unit: str, decimals: int) -> str:
    frame = comparison.summary()
    rows = [[name, f"{row.mae:,.{decimals}f} {unit}", f"{row.mape_pct:.2f}%"] for name, row in frame.iterrows()]
    return (
        table(["Forecast", "MAE", "MAPE"], rows)
        + f"\n\nScored on the {int(frame['half_hours'].iloc[0]):,} half-hours every forecast covers."
    )


def lead_table(comparison: Comparison, unit: str) -> str:
    frame = by_lead(comparison)
    rows = [[bucket, *(f"{frame.loc[bucket, name]:,.0f} {unit}" for name in frame.columns)] for bucket in frame.index]
    return table(["Lead time", *(f"MAE {name}" for name in frame.columns)], rows)


def report(sections: Sequence[tuple[str, str]], generated: dt.date) -> str:
    body = "\n\n".join(f"## {title}\n\n{text}" for title, text in sections)
    return (
        "# Model studies (Phase 5)\n\n"
        f"Generated {generated} by `python -m gridpulse_forecasting studies` "
        "([studies.py](../../services/forecasting/src/gridpulse_forecasting/studies.py)). Every number is a point "
        "forecast (P50) from a model refitted month by month, exactly as the headline backtests do, so they compare "
        "with each other; the design decisions they led to are in [ADR-0016](../adr/0016-model-studies.md).\n\n"
        f"{body}\n"
    )
