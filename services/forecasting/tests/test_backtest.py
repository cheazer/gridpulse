from __future__ import annotations

import datetime as dt
from typing import ClassVar

import numpy as np
import pandas as pd
import pytest

from gridpulse_forecasting.backtest import backtest_months, run_backtest, training_rows
from gridpulse_forecasting.dataset import day_ahead_issue_time, london_day
from gridpulse_forecasting.features import FEATURES
from gridpulse_forecasting.metrics import point_metrics
from gridpulse_forecasting.models import SeasonalNaive


def examples(first: str = "2025-01-01", last: str = "2025-04-10") -> pd.DataFrame:
    """Day-ahead examples with simple features: demand_lag_7d is the target a week earlier, plus noise."""
    rng = np.random.default_rng(0)
    parts = []
    for day in pd.date_range(first, last, freq="D").date:
        periods = london_day(day)
        part = pd.DataFrame({"target_time": periods, "issue_time": day_ahead_issue_time(day)})
        parts.append(part)
    frame = pd.concat(parts, ignore_index=True)
    frame["target"] = 25_000 + 5_000 * np.sin(np.arange(len(frame)) * 2 * np.pi / 48)
    for feature in FEATURES:
        frame[feature] = 0.0
    frame["demand_lag_7d"] = frame["target"] + rng.normal(0, 500, len(frame))
    return frame


class Spy:
    """Predicts a constant and remembers what it was trained on."""

    name = "spy"
    fits: ClassVar[list[pd.DataFrame]] = []

    def fit(self, features: pd.DataFrame, target: pd.Series) -> None:
        Spy.fits.append(features.assign(target=target))

    def predict(self, features: pd.DataFrame) -> pd.DataFrame:
        return pd.DataFrame({"p10": 20_000.0, "p50": 25_000.0, "p90": 30_000.0}, index=features.index.copy())

    def params(self) -> dict[str, str | int | float]:
        return {}


@pytest.fixture(autouse=True)
def reset_spy() -> None:
    Spy.fits = []


def test_test_months_are_the_last_complete_ones() -> None:
    # Data runs 1 Jan to 10 Apr: April is incomplete, so the last two complete months are Feb and Mar.
    assert [str(m) for m in backtest_months(examples(), 2)] == ["2025-02", "2025-03"]


def test_training_only_uses_actuals_published_by_the_refit_time() -> None:
    frame = examples()
    refit_time = pd.Timestamp("2025-02-28T13:00Z")

    train = training_rows(frame, refit_time)

    # The 11:30-12:00 period is published at 13:00 (an hour after it ends); the 12:00 one isn't yet.
    assert train["target_time"].max() == pd.Timestamp("2025-02-28T11:30Z")


def test_each_month_refits_a_fresh_model_on_what_was_known_then() -> None:
    frame = examples()
    neso = frame.set_index("target_time")["target"] * 1.02

    result = run_backtest(Spy, frame, neso, months=2)

    assert len(Spy.fits) == 2
    for fit, month in zip(Spy.fits, ["2025-02", "2025-03"], strict=True):
        first_issue = result.predictions.loc[result.predictions["fold"] == month, "issue_time"].min()
        assert first_issue == day_ahead_issue_time(dt.date.fromisoformat(f"{month}-01"))
        trained_until = frame.loc[fit.index, "target_time"].max()
        assert trained_until + pd.Timedelta(minutes=90) <= first_issue
    # The second fold trains on more history than the first: an expanding window.
    assert len(Spy.fits[1]) > len(Spy.fits[0])


def test_predictions_cover_every_half_hour_of_the_test_months() -> None:
    frame = examples()
    result = run_backtest(Spy, frame, frame.set_index("target_time")["target"], months=2)

    by_fold = result.predictions.groupby("fold").size()
    assert by_fold.to_dict() == {"2025-02": 28 * 48, "2025-03": 31 * 48 - 2}  # 30 March has 46 periods


def test_every_forecast_is_scored_on_the_same_half_hours() -> None:
    frame = examples()
    neso = frame.set_index("target_time")["target"] * 1.02
    neso.iloc[-24 * 48 :] = np.nan  # NESO missing for part of the last month

    result = run_backtest(Spy, frame, neso, months=2)

    half_hours = result.summary["half_hours"]
    assert (half_hours == half_hours.iloc[0]).all()
    common = result.predictions.dropna(subset=["neso"])
    assert result.summary.loc["neso", "half_hours"] == len(common)
    assert result.summary.loc["neso", "mape_pct"] == pytest.approx(2.0)


def test_no_overlap_with_neso_is_an_error_not_an_empty_score() -> None:
    with pytest.raises(ValueError, match="no half-hours"):
        run_backtest(Spy, examples(), pd.Series(dtype="float64"), months=2)


def test_quantile_forecasts_get_pinball_loss_and_coverage() -> None:
    frame = examples()
    result = run_backtest(Spy, frame, frame.set_index("target_time")["target"], months=1)

    model = result.summary.loc["model"]
    # The synthetic actuals run from 20,000 to 30,000 MW, so the spy's interval contains all of them.
    assert model["coverage_p10_p90"] == pytest.approx(1.0)
    assert {"pinball_p10", "pinball_p50", "pinball_p90"} <= set(result.summary.columns)
    assert np.isnan(result.summary.loc["neso", "coverage_p10_p90"])


def test_seasonal_naive_as_the_model_matches_the_seasonal_naive_benchmark() -> None:
    frame = examples()

    result = run_backtest(SeasonalNaive, frame, frame.set_index("target_time")["target"], months=2)

    assert result.summary.loc["model", "mape_pct"] == pytest.approx(result.summary.loc["seasonal_naive", "mape_pct"])
    common = result.predictions
    expected = point_metrics(common["actual"], common["seasonal_naive"])["mape_pct"]
    assert result.summary.loc["seasonal_naive", "mape_pct"] == pytest.approx(expected)
    assert list(result.by_month.index) == ["2025-02", "2025-03"]


def test_the_naive_benchmark_comes_from_the_tasks_own_lag() -> None:
    frame = examples()
    frame["carbon_lag_7d"] = 123.0

    result = run_backtest(Spy, frame, frame.set_index("target_time")["target"], months=1, naive_feature="carbon_lag_7d")

    assert (result.predictions["seasonal_naive"] == 123.0).all()


def test_rolling_examples_forecast_each_half_hour_several_times() -> None:
    # Two issue times covering the same half-hours, as rolling examples do.
    first = examples()
    second = first.assign(
        issue_time=first["issue_time"] - pd.Timedelta(hours=6), demand_lag_7d=first["demand_lag_7d"] + 1
    )
    both = pd.concat([first, second], ignore_index=True)

    result = run_backtest(Spy, both, both.drop_duplicates("target_time").set_index("target_time")["target"], months=1)

    march = result.predictions
    assert march["target_time"].duplicated().any()
    # Each row keeps its own naive value rather than one looked up by half-hour.
    assert sorted(march.groupby("target_time")["seasonal_naive"].nunique().unique()) == [2]


def test_a_month_is_complete_by_its_half_hours_not_its_rows() -> None:
    # Data to 10 April, each half-hour forecast eight times: April has plenty of rows but only 10 days.
    frame = examples()
    many = pd.concat([frame] * 8, ignore_index=True)

    assert [str(m) for m in backtest_months(many, 1)] == ["2025-03"]
