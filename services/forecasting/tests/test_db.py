from __future__ import annotations

import datetime as dt

import pandas as pd
import psycopg
import pytest
from pydantic import SecretStr

from gridpulse_forecasting.config import DatabaseSettings
from gridpulse_forecasting.db import Database
from gridpulse_forecasting.sources import load_carbon_actuals, load_neso_carbon_final

pytestmark = pytest.mark.integration


def is_utc(column: pd.Series) -> bool:
    # pandas 3 picks the resolution (us for Python datetimes), so check the zone, not the exact dtype string.
    return isinstance(column.dtype, pd.DatetimeTZDtype) and str(column.dtype.tz) == "UTC"


@pytest.fixture(scope="module", autouse=True)
def one_settled_half_hour(admin_conninfo: str) -> None:
    with psycopg.connect(admin_conninfo, autocommit=True) as conn:
        conn.execute(
            """
            INSERT INTO grid.demand VALUES
                (gen_random_uuid(), '2025-06-01T11:00:00Z', '2025-06-01T11:30:00Z', '2025-06-01', 25,
                 NULL, now(), 'neso-data-portal', 'nd', 'GB', '1.1.0', 'ND', 21500)
            ON CONFLICT DO NOTHING
            """
        )


def test_reads_views_as_dataframes_with_utc_timestamps(reader_settings: DatabaseSettings) -> None:
    frame = Database(reader_settings).read_frame(
        "SELECT valid_from, demand_mw, source FROM national_demand_actual WHERE valid_from = %s",
        ["2025-06-01T11:00:00Z"],
    )

    assert frame.to_dict("records") == [
        {"valid_from": frame["valid_from"].iloc[0], "demand_mw": 21500, "source": "neso-data-portal"}
    ]
    assert is_utc(frame["valid_from"])
    assert frame["valid_from"].iloc[0].isoformat() == "2025-06-01T11:00:00+00:00"


def test_empty_results_keep_typed_timestamp_columns(reader_settings: DatabaseSettings) -> None:
    frame = Database(reader_settings).read_frame("SELECT valid_from, forecast_mw FROM ndf_day_ahead WHERE false")

    assert frame.empty
    assert list(frame.columns) == ["valid_from", "forecast_mw"]
    assert is_utc(frame["valid_from"])


def test_sessions_are_read_only(reader_settings: DatabaseSettings) -> None:
    with Database(reader_settings).connect() as conn, pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
        conn.execute("DELETE FROM demand")


def test_login_cannot_write_even_outside_read_only_mode(reader_settings: DatabaseSettings) -> None:
    with Database(reader_settings).connect() as conn:
        conn.execute("SET default_transaction_read_only = off")
        conn.commit()
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            conn.execute("DELETE FROM demand")


def test_wrong_password_is_rejected(reader_settings: DatabaseSettings) -> None:
    wrong = reader_settings.model_copy(update={"password": SecretStr("not-it")})

    with pytest.raises(psycopg.OperationalError):
        Database(wrong).connect()


def test_carbon_loaders_treat_a_zero_actual_as_missing(admin_conninfo: str, reader_settings: DatabaseSettings) -> None:
    with psycopg.connect(admin_conninfo, autocommit=True) as conn:
        conn.execute(
            """
            INSERT INTO grid.carbon_intensity VALUES
                (gen_random_uuid(), '2025-06-02T11:00:00Z', '2025-06-02T11:30:00Z', '2025-06-02', 25, NULL, now(),
                 'neso-carbon-intensity', 'intensity', 'GB', '1.0.0', 120, 118, 'moderate'),
                (gen_random_uuid(), '2025-06-02T11:30:00Z', '2025-06-02T12:00:00Z', '2025-06-02', 26, NULL, now(),
                 'neso-carbon-intensity', 'intensity', 'GB', '1.0.0', 62, 0, 'low'),
                (gen_random_uuid(), '2025-06-02T11:00:00Z', '2025-06-02T11:30:00Z', '2025-06-02', 25, NULL, now(),
                 'neso-carbon-intensity', 'intensity', 'london', '1.0.0', 90, 95, 'low')
            ON CONFLICT DO NOTHING
            """
        )
    db = Database(reader_settings)
    start, end = dt.datetime(2025, 6, 2, tzinfo=dt.UTC), dt.datetime(2025, 6, 3, tzinfo=dt.UTC)

    actuals = load_carbon_actuals(db, start, end)
    benchmark = load_neso_carbon_final(db, start, end)

    assert actuals.to_dict() == {pd.Timestamp("2025-06-02T11:00Z"): 118.0}  # national only, the zero dropped
    assert len(benchmark) == 2  # the forecast is still NESO's forecast, even where the actual was bad
