from __future__ import annotations

import pandas as pd
import pytest
from fastapi.testclient import TestClient
from test_job_store import rows

from gridpulse_forecasting import job
from gridpulse_forecasting.api import PostgresRepository, create_app
from gridpulse_forecasting.config import DatabaseSettings, JobDatabaseSettings
from gridpulse_forecasting.db import Database

pytestmark = pytest.mark.integration


def test_serves_what_the_job_stored_through_the_read_only_login(
    job_settings: JobDatabaseSettings, reader_settings: DatabaseSettings
) -> None:
    issue = pd.Timestamp("2025-08-01T06:00Z")
    job.store(Database(job_settings, read_only=False), rows(28_000, issue))

    with TestClient(create_app(lambda: PostgresRepository(reader_settings))) as client:
        assert client.get("/ready").json() == {"status": "ready"}
        body = client.get(
            "/v1/forecasts/demand", params={"from": "2025-08-01T06:00Z", "to": "2025-08-01T08:00Z"}
        ).json()

    assert [h["p50"] for h in body["half_hours"]] == [28_000.0] * 4
    assert body["latest_issued_at"] == "2025-08-01T06:00:00Z"
    assert body["unit"] == "MW"
    assert body["stale"] is True  # issued long ago


def test_readiness_fails_cleanly_when_the_database_is_unreachable(reader_settings: DatabaseSettings) -> None:
    unreachable = reader_settings.model_copy(update={"port": 1})

    with TestClient(create_app(lambda: PostgresRepository(unreachable))) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/ready").status_code == 503
