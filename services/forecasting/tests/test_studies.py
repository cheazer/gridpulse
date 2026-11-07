"""The model studies' plumbing: same half-hours for every forecast, errors by lead time, NESO as an input."""

from __future__ import annotations

import pandas as pd
import pytest
from test_backtest import Spy, examples

from gridpulse_forecasting.backtest import run_backtest
from gridpulse_forecasting.models import NESO_FEATURE, SeasonalNaive
from gridpulse_forecasting.studies import Comparison, bucket_labels, by_lead, with_neso_forecast


def neso_for(frame: pd.DataFrame) -> pd.Series:
    return pd.Series(25_000.0, index=pd.DatetimeIndex(frame["target_time"]).unique())


def test_every_forecast_in_a_comparison_is_scored_on_the_same_half_hours() -> None:
    frame = examples()
    neso = neso_for(frame)
    neso.iloc[:100] = float("nan")  # NESO missing for some half-hours: they drop out for everyone

    comparison = Comparison(
        {
            "spy": run_backtest(Spy, frame, neso, months=2),
            "naive": run_backtest(SeasonalNaive, frame, neso, months=2),
        }
    )
    summary = comparison.summary()

    assert list(summary.index) == ["spy", "naive", "NESO"]
    assert (summary["half_hours"] == summary["half_hours"].iloc[0]).all()
    assert summary.loc["NESO", "mae"] == pytest.approx(summary.loc["spy", "mae"])  # both forecast 25,000 flat


def test_errors_are_grouped_by_lead_time() -> None:
    frame = examples()
    comparison = Comparison({"spy": run_backtest(Spy, frame, neso_for(frame), months=1)})

    table = by_lead(comparison)

    # Day-ahead examples: issued 13:00 the day before, so leads of 11 to 35 hours.
    assert set(table.index) <= set(bucket_labels())
    assert {"6-12 h", "12-24 h", "24-36 h"} >= set(table.index)


def test_neso_s_forecast_is_added_per_target_half_hour() -> None:
    frame = examples()
    neso = neso_for(frame) + pd.Series(range(len(neso_for(frame))), index=neso_for(frame).index)

    with_neso = with_neso_forecast(frame, neso)

    assert (with_neso[NESO_FEATURE].to_numpy() == neso.reindex(frame["target_time"]).to_numpy()).all()
