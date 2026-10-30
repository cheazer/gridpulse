"""A migrated TimescaleDB container, shared by the integration tests in one session."""

from __future__ import annotations

import re
from collections.abc import Iterator
from pathlib import Path
from typing import LiteralString, cast

import psycopg
import pytest

from gridpulse_forecasting.config import DatabaseSettings

REPO = Path(__file__).resolve().parents[3]
MIGRATIONS = REPO / "db" / "migrations"
READER_PASSWORD = "reader-test-password"  # throwaway container; never a real credential


def timescale_image() -> str:
    """The image Compose runs, so tests and the stack can't drift apart."""
    infra = (REPO / "deploy" / "compose" / "infra.yaml").read_text()
    match = re.search(r"image:\s*(timescale/timescaledb:\S+)", infra)
    assert match, "no TimescaleDB image in deploy/compose/infra.yaml"
    return match.group(1)


def migration_version(path: Path) -> int:
    return int(path.name[1:].split("__", 1)[0])


@pytest.fixture(scope="session")
def admin_conninfo() -> Iterator[str]:
    """Superuser connection string to a database migrated as Flyway would (schema grid, files in version order)."""
    from testcontainers.community.postgres import PostgresContainer

    with PostgresContainer(timescale_image(), username="admin", password="admin", dbname="gridpulse") as pg:
        conninfo = (
            f"host={pg.get_container_host_ip()} port={pg.get_exposed_port(5432)} "
            "dbname=gridpulse user=admin password=admin"
        )
        with psycopg.connect(conninfo, autocommit=True) as conn:
            conn.execute("CREATE SCHEMA grid")
            conn.execute("SET search_path = grid, public")  # as Flyway: the image installs timescaledb in public
            for path in sorted(MIGRATIONS.glob("V*__*.sql"), key=migration_version):
                with conn.transaction():
                    conn.execute(cast(LiteralString, path.read_text()))
            # What deploy/compose/scripts/create-db-users.sh does for the real stack.
            conn.execute(f"CREATE ROLE forecasting LOGIN PASSWORD '{READER_PASSWORD}' IN ROLE grid_reader")
        yield conninfo


@pytest.fixture
def reader_settings(admin_conninfo: str) -> DatabaseSettings:
    params = dict(item.split("=", 1) for item in admin_conninfo.split())
    return DatabaseSettings.model_validate(
        {
            "POSTGRES_HOST": params["host"],
            "TIMESCALE_PORT": params["port"],
            "POSTGRES_DB": params["dbname"],
            "FORECAST_DB_USER": "forecasting",
            "FORECAST_DB_PASSWORD": READER_PASSWORD,
        }
    )
