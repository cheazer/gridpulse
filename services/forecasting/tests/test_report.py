from __future__ import annotations

import datetime as dt

from test_backtest import Spy, examples

from gridpulse_forecasting.backtest import run_backtest
from gridpulse_forecasting.models import SeasonalNaive
from gridpulse_forecasting.report import accuracy_report, table
from gridpulse_forecasting.tasks import CARBON, DEMAND


def test_report_has_every_section_for_a_quantile_model() -> None:
    frame = examples()
    result = run_backtest(Spy, frame, frame.set_index("target_time")["target"] * 1.02, months=2)

    report = accuracy_report(result, DEMAND, "spy", "abc123", dt.date(2026, 10, 30))

    for heading in ["## Headline", "## Prediction intervals", "## MAPE by month", "## MAPE by time of day (UK)"]:
        assert heading in report
    assert "| NESO day-ahead | 2.00% |" in report
    assert "2025-02 to 2025-03" in report
    assert "(MLflow run `abc123`)" in report
    assert "| 100.0% |" in report  # the spy's 20-30 GW band covers every synthetic actual


def test_report_skips_interval_and_importance_sections_a_model_lacks() -> None:
    frame = examples()
    result = run_backtest(SeasonalNaive, frame, frame.set_index("target_time")["target"], months=1)

    report = accuracy_report(result, DEMAND, "seasonal-naive", None, dt.date(2026, 10, 30))

    assert "## Prediction intervals" not in report
    assert "## What the model leans on" not in report


def test_tables_right_align_numbers() -> None:
    assert table(["Name", "Value"], [["a", 1]]) == "| Name | Value |\n| --- | ---: |\n| a | 1 |"


def test_carbon_report_leads_with_mae_and_labels_the_benchmark_honestly() -> None:
    frame = examples()
    frame["target"] = frame["target"] / 100  # synthetic "carbon intensity" of 200-300 g/kWh
    frame["demand_lag_7d"] = frame["demand_lag_7d"] / 100
    result = run_backtest(Spy, frame, frame.set_index("target_time")["target"] * 1.1, months=1)

    report = accuracy_report(result, CARBON, "spy", None, dt.date(2026, 10, 30))

    assert report.startswith("# Day-ahead carbon intensity forecast accuracy")
    assert "| Forecast | MAE | MAPE | RMSE | Bias |" in report
    assert "| NESO final (not day-ahead) |" in report
    assert "## MAE by month" in report and "## MAE by time of day (UK)" in report
    assert "gCO2/kWh" in report
    assert "is not a day-ahead forecast" in report


def test_rolling_report_breaks_accuracy_down_by_lead_time() -> None:
    frame = examples()
    result = run_backtest(Spy, frame, frame.set_index("target_time")["target"], months=1)

    report = accuracy_report(result, DEMAND, "spy", None, dt.date(2026, 10, 30), rolling=True)

    assert "## MAPE by lead time" in report
    assert "every 6 hours" in report
    # Day-ahead synthetic examples have leads of 11 to 35 hours.
    assert "| 12-24 h |" in report and "| 0-6 h |" not in report
