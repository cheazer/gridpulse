"""HTTP API serving GridPulse's stored forecasts (ADR-0011, step 2.6b).

Read-only by construction: it connects as the ``forecasting`` login (grid_reader, sessions read-only) and only
reads ``model_forecast_latest``, which the forecast job fills. Nothing is computed per request, so responses are
fast and every caller sees the same numbers.

Run locally:  uv run --env-file ../../.env uvicorn gridpulse_forecasting.api:app --port 8000
"""

from __future__ import annotations

import datetime as dt
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from enum import StrEnum
from typing import Annotated, Any, Protocol

import psycopg
from fastapi import Depends, FastAPI, HTTPException, Query, Request
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool
from pydantic import AwareDatetime, BaseModel, Field

from gridpulse_forecasting.config import DatabaseSettings
from gridpulse_forecasting.db import READ_ONLY, SESSION_OPTIONS

DEFAULT_WINDOW = dt.timedelta(hours=48)
MAX_WINDOW = dt.timedelta(days=7)
# The job runs at least every 6 hours (Phase 4 schedules it); older than this and something is wrong upstream.
STALE_AFTER = dt.timedelta(hours=7)


class Task(StrEnum):
    demand = "demand"
    carbon = "carbon"


class HalfHour(BaseModel):
    valid_from: AwareDatetime
    valid_to: AwareDatetime
    p10: float = Field(description="10th percentile: 1 in 10 chance the actual is lower")
    p50: float = Field(description="Median: the point forecast")
    p90: float = Field(description="90th percentile: 1 in 10 chance the actual is higher")
    issued_at: AwareDatetime = Field(description="When this forecast was made: nothing later was used")
    model: str
    model_version: str


class Forecast(BaseModel):
    task: Task
    unit: str | None = Field(description="MW for demand, gCO2/kWh for carbon intensity; null if nothing stored")
    window_from: AwareDatetime
    window_to: AwareDatetime
    latest_issued_at: AwareDatetime | None
    stale: bool = Field(description=f"True if the newest forecast was issued more than {STALE_AFTER} ago")
    half_hours: list[HalfHour]


class Repository(Protocol):
    def latest(self, task: str, start: dt.datetime, end: dt.datetime) -> list[dict[str, Any]]: ...

    def ping(self) -> None: ...


LATEST = """
SELECT valid_from, valid_to, p10, p50, p90, issued_at, model, model_version, unit
  FROM model_forecast_latest
 WHERE task = %(task)s AND valid_from >= %(start)s AND valid_from < %(end)s
 ORDER BY valid_from
"""


class PostgresRepository:
    """Forecasts from TimescaleDB through a small connection pool, as the read-only service login."""

    def __init__(self, settings: DatabaseSettings) -> None:
        self._pool = ConnectionPool(
            kwargs={
                "host": settings.host,
                "port": settings.port,
                "dbname": settings.dbname,
                "user": settings.user,
                "password": settings.password.get_secret_value(),
                "options": SESSION_OPTIONS + READ_ONLY,
                "application_name": "gridpulse-forecasting-api",
            },
            min_size=1,
            max_size=4,
            open=False,
        )

    def open(self) -> None:
        # Don't block start-up on the database: /ready reports whether it's reachable.
        self._pool.open(wait=False)

    def close(self) -> None:
        self._pool.close()

    def latest(self, task: str, start: dt.datetime, end: dt.datetime) -> list[dict[str, Any]]:
        with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            cur.execute(LATEST, {"task": task, "start": start, "end": end})
            return cur.fetchall()

    def ping(self) -> None:
        with self._pool.connection(timeout=2) as conn:
            conn.execute("SELECT 1")


def get_repository(request: Request) -> Repository:
    repository: Repository = request.app.state.repository
    return repository


# FastAPI resolves dependencies from annotations, so they must be importable names, not locals of create_app.
RepositoryDep = Annotated[Repository, Depends(get_repository)]


def create_app(repository_factory: Callable[[], Repository] | None = None) -> FastAPI:
    """The app; tests pass a fake repository, production builds one from the environment."""

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        repository = repository_factory() if repository_factory else PostgresRepository(DatabaseSettings())
        if isinstance(repository, PostgresRepository):
            repository.open()
        app.state.repository = repository
        yield
        if isinstance(repository, PostgresRepository):
            repository.close()

    app = FastAPI(
        title="GridPulse forecasting",
        version="0.1.0",
        summary="Day-ahead and 48-hour forecasts of GB national demand and carbon intensity, with P10-P90 bands.",
        lifespan=lifespan,
    )

    @app.get("/health", tags=["ops"])
    def health() -> dict[str, str]:
        """Liveness: the process is up. Doesn't touch the database, so a database outage doesn't restart it."""
        return {"status": "ok"}

    @app.get("/ready", tags=["ops"])
    def ready(repo: RepositoryDep) -> dict[str, str]:
        """Readiness: the database answers. Load balancers send traffic only when this is 200."""
        try:
            repo.ping()
        except (psycopg.Error, OSError, TimeoutError) as error:
            raise HTTPException(503, f"database unavailable: {type(error).__name__}") from error
        return {"status": "ready"}

    @app.get("/v1/forecasts/{task}", tags=["forecasts"])
    def forecasts(
        task: Task,
        repo: RepositoryDep,
        start: Annotated[AwareDatetime | None, Query(alias="from", description="Default: now")] = None,
        end: Annotated[AwareDatetime | None, Query(alias="to", description="Default: 48 hours after from")] = None,
    ) -> Forecast:
        """The newest forecast for each half-hour starting in [from, to), in UTC."""
        now = dt.datetime.now(dt.UTC)
        window_from = start or now.replace(minute=0 if now.minute < 30 else 30, second=0, microsecond=0)
        window_to = end or window_from + DEFAULT_WINDOW
        if window_to <= window_from:
            raise HTTPException(422, "'to' must be after 'from'")
        if window_to - window_from > MAX_WINDOW:
            raise HTTPException(422, f"the window can be at most {MAX_WINDOW.days} days")

        rows = repo.latest(task.value, window_from, window_to)
        latest = max((row["issued_at"] for row in rows), default=None)
        return Forecast(
            task=task,
            unit=rows[0]["unit"] if rows else None,
            window_from=window_from,
            window_to=window_to,
            latest_issued_at=latest,
            stale=latest is None or now - latest > STALE_AFTER,
            half_hours=[HalfHour(**{k: v for k, v in row.items() if k != "unit"}) for row in rows],
        )

    return app


app = create_app()
