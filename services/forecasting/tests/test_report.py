from __future__ import annotations

import datetime as dt

from test_backtest import Spy, examples

from gridpulse_forecasting.backtest import run_backtest
from gridpulse_forecasting.models import SeasonalNaive
from gridpulse_forecasting.report import accuracy_report, table


def test_report_has_every_section_for_a_quantile_model() -> None:
    frame = examples()
    result = run_backtest(Spy, frame, frame.set_index("target_time")["target_mw"] * 1.02, months=2)

    report = accuracy_report(result, "spy", "abc123", dt.date(2026, 10, 30))

    for heading in ["## Headline", "## Prediction intervals", "## MAPE by month", "## MAPE by time of day (UK)"]:
        assert heading in report
    assert "| NESO day-ahead | 2.00% |" in report
    assert "2025-02 to 2025-03" in report
    assert "(MLflow run `abc123`)" in report
    assert "| 100.0% |" in report  # the spy's 20-30 GW band covers every synthetic actual


def test_report_skips_interval_and_importance_sections_a_model_lacks() -> None:
    frame = examples()
    result = run_backtest(SeasonalNaive, frame, frame.set_index("target_time")["target_mw"], months=1)

    report = accuracy_report(result, "seasonal-naive", None, dt.date(2026, 10, 30))

    assert "## Prediction intervals" not in report
    assert "## What the model leans on" not in report


def test_tables_right_align_numbers() -> None:
    assert table(["Name", "Value"], [["a", 1]]) == "| Name | Value |\n| --- | ---: |\n| a | 1 |"
