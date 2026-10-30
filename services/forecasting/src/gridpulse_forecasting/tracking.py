"""Record backtests in MLflow when a tracking server is configured (ADR-0011).

MLflow reads ``MLFLOW_TRACKING_URI`` itself; without it, nothing is logged and the backtest still runs, so unit
tests and quick local runs need no server.
"""

from __future__ import annotations

import os
import subprocess

from gridpulse_forecasting.backtest import BacktestResult
from gridpulse_forecasting.features import FEATURES
from gridpulse_forecasting.models import Forecaster

EXPERIMENT = "day-ahead-demand"


def log_backtest(result: BacktestResult, model: Forecaster) -> str | None:
    """Log parameters, metrics for the model and both benchmarks, and the result tables. Returns the run id."""
    if not os.environ.get("MLFLOW_TRACKING_URI"):
        return None
    import mlflow  # imported lazily: only runs that log need it

    mlflow.set_experiment(EXPERIMENT)
    folds = sorted(result.predictions["fold"].unique())
    with mlflow.start_run(run_name=model.name) as run:
        mlflow.set_tags({"model": model.name, "git_commit": git_commit()})
        mlflow.log_params(
            {
                **model.params(),
                "test_months": len(folds),
                "test_from": folds[0],
                "test_to": folds[-1],
                "issue_time": "13:00 UK on D-1",
                "features": ",".join(FEATURES),
            }
        )
        # Every forecast's metrics in one run, prefixed: model_mape_pct, neso_mape_pct, seasonal_naive_mape_pct.
        mlflow.log_metrics(
            {
                f"{forecast}_{metric}": float(value)
                for forecast, row in result.summary.iterrows()
                for metric, value in row.dropna().items()
            }
        )
        mlflow.log_text(result.summary.to_csv(), "summary.csv")
        mlflow.log_text(result.by_month.to_csv(), "mape_by_month.csv")
        return str(run.info.run_id)


def git_commit() -> str:
    """The commit the code ran from, so a run can be reproduced; "unknown" outside a git checkout."""
    try:
        out = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return "unknown"
    return out.stdout.strip()
