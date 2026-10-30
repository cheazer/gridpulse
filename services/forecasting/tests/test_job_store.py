from __future__ import annotations

import pandas as pd
import psycopg
import pytest

from gridpulse_forecasting import job
from gridpulse_forecasting.config import DatabaseSettings, JobDatabaseSettings
from gridpulse_forecasting.db import Database

pytestmark = pytest.mark.integration

ISSUE = pd.Timestamp("2025-07-01T09:00Z")


def rows(p50: float, issue: pd.Timestamp = ISSUE) -> pd.DataFrame:
    periods = pd.date_range(issue, periods=4, freq="30min")
    return pd.DataFrame(
        {
            "task": "demand",
            "model": "lightgbm-cqr",
            "model_version": "test",
            "issued_at": issue,
            "valid_from": periods,
            "valid_to": periods + pd.Timedelta(minutes=30),
            "unit": "MW",
            "p10": p50 - 1_000,
            "p50": p50,
            "p90": p50 + 1_000,
        }
    )


def latest(reader: DatabaseSettings) -> pd.DataFrame:
    return Database(reader).read_frame(
        "SELECT issued_at, valid_from, p50 FROM model_forecast_latest WHERE task = 'demand' ORDER BY valid_from"
    )


def test_a_rerun_for_the_same_issue_time_replaces_the_forecast(
    job_settings: JobDatabaseSettings, reader_settings: DatabaseSettings
) -> None:
    db = Database(job_settings, read_only=False)

    assert job.store(db, rows(30_000)) == 4
    job.store(db, rows(31_000))

    stored = latest(reader_settings)
    assert stored["p50"].tolist() == [31_000.0] * 4


def test_the_api_view_serves_the_newest_issue_per_half_hour(
    job_settings: JobDatabaseSettings, reader_settings: DatabaseSettings
) -> None:
    db = Database(job_settings, read_only=False)
    job.store(db, rows(31_000))
    later = ISSUE + pd.Timedelta(hours=1)  # covers the last two half-hours again
    job.store(db, rows(32_000, later))

    stored = latest(reader_settings).set_index("valid_from")["p50"]
    assert stored[ISSUE] == 31_000
    assert stored[later] == 32_000


def test_forecasts_with_missing_values_are_refused(job_settings: JobDatabaseSettings) -> None:
    broken = rows(30_000)
    broken.loc[2, "p50"] = float("nan")

    with pytest.raises(ValueError, match="missing values"):
        job.store(Database(job_settings, read_only=False), broken)


def test_the_read_only_service_login_cannot_store_forecasts(reader_settings: DatabaseSettings) -> None:
    with pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
        job.store(Database(reader_settings), rows(30_000))


def test_the_job_login_cannot_touch_event_data(job_settings: JobDatabaseSettings) -> None:
    with Database(job_settings, read_only=False).connect() as conn, pytest.raises(psycopg.errors.InsufficientPrivilege):
        conn.execute("DELETE FROM demand")
