"""The forecast job: train on everything known, forecast the next 48 hours, store it (ADR-0011, V7).

It runs on a schedule (a CronJob in Phase 4) and the API only reads what it stored, so requests are fast and every
caller sees the same numbers. From step 2.7 it loads the registered champion instead of training each run.
"""

from __future__ import annotations

import datetime as dt
from typing import Any

import pandas as pd

from gridpulse_forecasting.backtest import training_rows
from gridpulse_forecasting.dataset import HORIZON, build_rolling, rolling_issue_times, rolling_targets
from gridpulse_forecasting.db import Database
from gridpulse_forecasting.features import FEATURES, LAG_DAYS, LONDON, build_features
from gridpulse_forecasting.sources import load_sources
from gridpulse_forecasting.tasks import Task
from gridpulse_forecasting.tracking import git_commit

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


def forecast(db: Database, task: Task, issue_time: pd.Timestamp, first_day: dt.date) -> pd.DataFrame:
    """Train the task's production model on what was known at ``issue_time`` and forecast the next 48 hours."""
    issue_day = issue_time.tz_convert(LONDON).date()
    start = dt.datetime.combine(first_day - dt.timedelta(days=max(LAG_DAYS) + 1), dt.time(), dt.UTC)
    end = (issue_time + HORIZON + pd.Timedelta(days=1)).to_pydatetime()
    sources = load_sources(db, start, end)
    targets = task.load_targets(db, start, issue_time.to_pydatetime())

    # Training: rolling examples issued before today, keeping only those whose actual had been published.
    examples = build_rolling(sources, targets, rolling_issue_times(first_day, issue_day - dt.timedelta(days=1)))
    train = training_rows(examples, issue_time)
    model = task.production()
    model.fit(train[FEATURES], train["target"])

    periods = rolling_targets(issue_time)
    predicted = model.predict(build_features(sources.as_of(issue_time), issue_time, periods)[FEATURES])
    return pd.DataFrame(
        {
            "task": task.name,
            "model": MODEL_NAME,
            "model_version": git_commit(),
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
