from __future__ import annotations

import datetime as dt
from collections.abc import Iterator
from typing import Any

import psycopg
import pytest
from fastapi.testclient import TestClient

from gridpulse_forecasting.api import create_app

UTC = dt.UTC


def row(valid_from: dt.datetime, issued_at: dt.datetime, p50: float = 30_000) -> dict[str, Any]:
    return {
        "valid_from": valid_from,
        "valid_to": valid_from + dt.timedelta(minutes=30),
        "p10": p50 - 1_000,
        "p50": p50,
        "p90": p50 + 1_000,
        "issued_at": issued_at,
        "model": "lightgbm-cqr",
        "model_version": "abc1234",
        "unit": "MW",
    }


class FakeRepository:
    """Answers like model_forecast_latest would, and records what it was asked."""

    def __init__(self, rows: list[dict[str, Any]] | None = None, healthy: bool = True) -> None:
        self.rows = rows or []
        self.healthy = healthy
        self.asked: list[tuple[str, dt.datetime, dt.datetime]] = []

    def latest(self, task: str, start: dt.datetime, end: dt.datetime) -> list[dict[str, Any]]:
        self.asked.append((task, start, end))
        return [r for r in self.rows if start <= r["valid_from"] < end]

    def ping(self) -> None:
        if not self.healthy:
            raise psycopg.OperationalError("connection refused")


def client_for(repository: FakeRepository) -> Iterator[TestClient]:
    with TestClient(create_app(lambda: repository)) as client:
        yield client


@pytest.fixture
def now() -> dt.datetime:
    return dt.datetime.now(UTC).replace(second=0, microsecond=0)


def test_health_never_touches_the_database() -> None:
    for client in client_for(FakeRepository(healthy=False)):
        assert client.get("/health").json() == {"status": "ok"}


def test_ready_reports_a_database_outage_as_503() -> None:
    for client in client_for(FakeRepository(healthy=False)):
        response = client.get("/ready")
        assert response.status_code == 503
        assert "OperationalError" in response.json()["detail"]
    for client in client_for(FakeRepository()):
        assert client.get("/ready").status_code == 200


def test_returns_the_stored_half_hours_in_the_window(now: dt.datetime) -> None:
    issued = now - dt.timedelta(hours=1)
    start = dt.datetime(2030, 1, 1, tzinfo=UTC)
    repo = FakeRepository([row(start + dt.timedelta(minutes=30 * i), issued, 30_000 + i) for i in range(4)])

    for client in client_for(repo):
        body = client.get(
            "/v1/forecasts/demand", params={"from": "2030-01-01T00:00Z", "to": "2030-01-01T01:00Z"}
        ).json()

    assert [h["p50"] for h in body["half_hours"]] == [30_000, 30_001]
    assert body["unit"] == "MW"
    assert body["half_hours"][0]["valid_from"] == "2030-01-01T00:00:00Z"
    assert body["stale"] is False
    assert repo.asked == [("demand", start, start + dt.timedelta(hours=1))]


def test_defaults_to_the_next_48_hours_from_the_current_half_hour() -> None:
    repo = FakeRepository()
    for client in client_for(repo):
        client.get("/v1/forecasts/carbon")

    task, start, end = repo.asked[0]
    assert task == "carbon"
    assert start.minute in (0, 30) and start.second == 0
    assert end - start == dt.timedelta(hours=48)
    assert dt.datetime.now(UTC) - start < dt.timedelta(minutes=30)


def test_an_old_forecast_is_flagged_stale(now: dt.datetime) -> None:
    repo = FakeRepository([row(now, now - dt.timedelta(hours=8))])
    for client in client_for(repo):
        body = client.get("/v1/forecasts/demand").json()

    assert body["stale"] is True


def test_nothing_stored_is_an_empty_stale_answer_not_an_error() -> None:
    for client in client_for(FakeRepository()):
        response = client.get("/v1/forecasts/demand")

    assert response.status_code == 200
    assert response.json()["half_hours"] == []
    assert response.json()["stale"] is True
    assert response.json()["unit"] is None


@pytest.mark.parametrize(
    ("params", "message"),
    [
        ({"from": "2030-01-02T00:00Z", "to": "2030-01-01T00:00Z"}, "must be after"),
        ({"from": "2030-01-01T00:00Z", "to": "2030-01-09T00:00Z"}, "at most 7 days"),
    ],
)
def test_rejects_bad_windows(params: dict[str, str], message: str) -> None:
    for client in client_for(FakeRepository()):
        response = client.get("/v1/forecasts/demand", params=params)

    assert response.status_code == 422
    assert message in response.json()["detail"]


def test_rejects_unknown_tasks_and_naive_timestamps() -> None:
    for client in client_for(FakeRepository()):
        assert client.get("/v1/forecasts/wind").status_code == 422
        # Without a zone, "18:00" is ambiguous on the UK clock; the API insists on UTC offsets.
        assert client.get("/v1/forecasts/demand", params={"from": "2030-01-01T18:00"}).status_code == 422


def test_openapi_documents_the_quantiles() -> None:
    for client in client_for(FakeRepository()):
        schema = client.get("/openapi.json").json()

    half_hour = schema["components"]["schemas"]["HalfHour"]["properties"]
    assert "1 in 10" in half_hour["p10"]["description"]
