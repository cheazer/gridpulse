from __future__ import annotations

from pathlib import Path

import pytest
from test_backtest import examples

from gridpulse_forecasting.backtest import run_backtest
from gridpulse_forecasting.models import SeasonalNaive
from gridpulse_forecasting.tracking import log_backtest

EXPERIMENT = "test-experiment"


def backtest_result():  # type: ignore[no-untyped-def]
    frame = examples()
    return run_backtest(SeasonalNaive, frame, frame.set_index("target_time")["target"] * 1.02, months=2)


def test_without_a_tracking_uri_nothing_is_logged(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("MLFLOW_TRACKING_URI", raising=False)

    assert log_backtest(backtest_result(), SeasonalNaive(), EXPERIMENT) is None


def test_logs_params_metrics_for_every_forecast_and_the_tables(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    import mlflow

    monkeypatch.setenv("MLFLOW_TRACKING_URI", tmp_path.as_uri())
    # A throwaway local store keeps this a unit test; real runs go to the Postgres-backed server in Compose.
    monkeypatch.setenv("MLFLOW_ALLOW_FILE_STORE", "true")
    result = backtest_result()

    run_id = log_backtest(result, SeasonalNaive(), EXPERIMENT)

    assert run_id is not None
    run = mlflow.MlflowClient().get_run(run_id)
    assert mlflow.MlflowClient().get_experiment(run.info.experiment_id).name == EXPERIMENT
    assert run.data.params["test_from"] == "2025-02"
    assert run.data.params["lag_days"] == "7"
    assert run.data.tags["model"] == "seasonal-naive"
    assert run.data.tags["stage"] == "test"
    assert run.data.metrics["neso_mape_pct"] == pytest.approx(2.0)
    assert run.data.metrics["model_mape_pct"] == pytest.approx(result.summary.loc["seasonal_naive", "mape_pct"])
    artifacts = {a.path for a in mlflow.MlflowClient().list_artifacts(run_id)}
    assert artifacts == {"summary.csv", "mape_by_month.csv"}
