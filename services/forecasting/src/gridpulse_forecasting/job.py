"""The forecast job: load the champion, forecast the next 48 hours, store it (ADR-0011, V7).

It runs on a schedule (a CronJob in Phase 4) and the API only reads what it stored, so requests are fast and every
caller sees the same numbers. Training is separate (``train``, training.py): the job only predicts, in seconds.
"""

from __future__ import annotations

from typing import Any

import pandas as pd

from gridpulse_forecasting.dataset import HORIZON, rolling_targets
from gridpulse_forecasting.db import Database
from gridpulse_forecasting.features import FEATURES, LAG_DAYS, build_features
from gridpulse_forecasting.registry import Registered
from gridpulse_forecasting.sources import load_sources
from gridpulse_forecasting.tasks import Task

MODEL_NAME = "lightgbm-cqr"

UPSERT = """
INSERT INTO model_forecast (task, model, model_version, issued_at, valid_from, valid_to, unit, p10, p50, p90)
VALUES (%(task)s, %(model)s, %(model_version)s, %(issued_at)s, %(valid_from)s, %(valid_to)s, %(unit)s,
        %(p10)s, %(p50)s, %(p90)s)
ON CONFLICT (task, model, issued_at, valid_from) DO UPDATE
   SET model_version = EXCLUDED.model_version, valid_to = EXCLUDED.valid_to, unit = EXCLUDED.unit,
       p10 = EXCLUDED.p10, p50 = EXCLUDED.p50, p90 = EXCLUDED.p90, created_at = now()
"""


def issue_time_now() -> pd.Timestamp:
    """The latest half-hour boundary: forecasts are issued on the settlement-period grid."""
    return pd.Timestamp.now(tz="UTC").floor("30min")


def forecast(db: Database, task: Task, issue_time: pd.Timestamp, champion: Registered) -> pd.DataFrame:
    """The champion's forecast of the next 48 hours, from what was known at ``issue_time``."""
    # Only as much history as the features reach back: the longest lag, plus a day for UK-day boundaries.
    start = (issue_time - pd.Timedelta(days=max(LAG_DAYS) + 2)).to_pydatetime()
    end = (issue_time + HORIZON + pd.Timedelta(days=1)).to_pydatetime()
    sources = load_sources(db, start, end)

    periods = rolling_targets(issue_time)
    predicted = champion.model.predict(build_features(sources.as_of(issue_time), issue_time, periods)[FEATURES])
    return pd.DataFrame(
        {
            "task": task.name,
            "model": MODEL_NAME,
            "model_version": champion.label,
            "issued_at": issue_time,
            "valid_from": periods,
            "valid_to": periods + pd.Timedelta(minutes=30),
            "unit": task.unit,
            "p10": predicted["p10"].to_numpy(),
            "p50": predicted["p50"].to_numpy(),
            "p90": predicted["p90"].to_numpy(),
        }
    )


def store(db: Database, rows: pd.DataFrame) -> int:
    """Upsert forecast rows in one transaction: a re-run for the same issue time replaces its forecast."""
    if rows[["p10", "p50", "p90"]].isna().any().any():
        raise ValueError("refusing to store a forecast with missing values")
    records: list[dict[str, Any]] = [
        {
            **{str(k): v for k, v in row.items()},
            "issued_at": row["issued_at"].to_pydatetime(),
            "valid_from": row["valid_from"].to_pydatetime(),
            "valid_to": row["valid_to"].to_pydatetime(),
        }
        for row in rows.to_dict("records")
    ]
    with db.connect() as conn, conn.cursor() as cur:
        cur.executemany(UPSERT, records)
    return len(records)
