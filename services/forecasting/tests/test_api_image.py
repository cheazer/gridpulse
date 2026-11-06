"""The API runs from an image without the "jobs" dependency group (Dockerfile, target api)."""

from __future__ import annotations

import os
import subprocess
import sys

JOBS_ONLY = ("holidays", "lightgbm", "mlflow", "pandas", "pyarrow")


def test_the_api_imports_nothing_from_the_jobs_group() -> None:
    # A fresh interpreter: in this one, other tests have already imported pandas.
    code = f"import sys, gridpulse_forecasting.api; print(','.join(m for m in {JOBS_ONLY!r} if m in sys.modules))"
    env = {**os.environ, "FORECAST_DB_PASSWORD": "unused", "OTEL_EXPORTER_OTLP_ENDPOINT": ""}
    result = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, env=env, check=True)

    assert result.stdout.strip() == "", f"the API imported {result.stdout.strip()}: its image doesn't have them"
