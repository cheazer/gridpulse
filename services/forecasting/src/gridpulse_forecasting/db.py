"""Read-only access to the grid schema, returning pandas DataFrames with UTC timestamps.

Writes are blocked twice: the login only holds grid_reader (SELECT), and every session starts with
default_transaction_read_only, so even a misconfigured login with write rights can't change data.
Forecasts are written by a separate, narrowly granted path in step 2.6.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any, LiteralString

import pandas as pd
import psycopg
from psycopg import sql

from gridpulse_forecasting.config import DatabaseSettings

# search_path: queries name tables without the grid prefix, as in the migrations.
# timezone=UTC: timestamptz values arrive as UTC datetimes, so pandas columns are UTC-aware datetimes.
SESSION_OPTIONS = "-c search_path=grid -c timezone=UTC -c default_transaction_read_only=on"

TIMESTAMPTZ_OID = psycopg.adapters.types["timestamptz"].oid

# What cursor.execute accepts on Python 3.12. LiteralString means a query can't be built by pasting values into
# a string at runtime: values go in params, identifiers through psycopg.sql.
Query = LiteralString | bytes | sql.SQL | sql.Composed


class Database:
    def __init__(self, settings: DatabaseSettings) -> None:
        self._settings = settings

    def connect(self) -> psycopg.Connection[tuple[Any, ...]]:
        s = self._settings
        return psycopg.connect(
            host=s.host,
            port=s.port,
            dbname=s.dbname,
            user=s.user,
            password=s.password.get_secret_value(),
            options=SESSION_OPTIONS,
            application_name="gridpulse-forecasting",
        )

    def read_frame(self, query: Query, params: Sequence[Any] | Mapping[str, Any] | None = None) -> pd.DataFrame:
        """Run one query and return its rows, with column names from the result."""
        with self.connect() as conn, conn.cursor() as cur:
            cur.execute(query, params)
            description = cur.description or []
            frame = pd.DataFrame(cur.fetchall(), columns=[c.name for c in description])
        # Type timestamptz columns from the result metadata, not the values: an empty result, or one
        # that is all NULL (issued_at on actuals), would otherwise come back as plain object columns.
        for column in description:
            if column.type_code == TIMESTAMPTZ_OID:
                frame[column.name] = pd.to_datetime(frame[column.name], utc=True)
        return frame
