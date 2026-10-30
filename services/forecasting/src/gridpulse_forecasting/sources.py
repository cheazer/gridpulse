"""Model inputs, each row stamped with when it became known, and the targets the model learns.

Every input row carries an ``available_at`` timestamp. ``Sources.as_of(t)`` drops every row not yet available at
``t``, and feature code only ever receives that snapshot, so it cannot see the future however it is written
(ADR-0011). The targets are kept apart: they are labels, never inputs.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field

import pandas as pd

from gridpulse_forecasting.db import Database

# Elexon publishes INDO for a settlement period within minutes of it ending. One hour is a conservative
# allowance: assuming data arrives later than it does can only make backtests pessimistic, never optimistic.
INDO_PUBLICATION_LAG = pd.Timedelta(hours=1)

# The Carbon Intensity API publishes a half-hour's actual shortly after it ends; the same conservative hour.
CARBON_PUBLICATION_LAG = pd.Timedelta(hours=1)

WEATHER_COLUMNS = [
    "temperature_2m_c",
    "wind_speed_10m_ms",
    "wind_speed_100m_ms",
    "shortwave_radiation_wm2",
    "cloud_cover_pct",
]


@dataclass(frozen=True)
class Sources:
    """Inputs known to the model, each with an ``available_at`` column (UTC)."""

    # valid_from, demand_mw, available_at: Elexon's initial national demand outturn, half-hourly.
    indo: pd.DataFrame
    # valid_from, location, issued_at, available_at, WEATHER_COLUMNS: Open-Meteo forecast revisions, hourly.
    # Reanalysis is deliberately absent: it is what happened, never what was known in advance (ADR-0008).
    weather: pd.DataFrame
    # valid_from, carbon_gco2_per_kwh, available_at: national carbon intensity actuals, half-hourly.
    carbon: pd.DataFrame = field(default_factory=lambda: empty_carbon())

    def as_of(self, t: pd.Timestamp) -> Sources:
        """Only what was available at ``t``."""
        return Sources(
            indo=self.indo[self.indo["available_at"] <= t],
            weather=self.weather[self.weather["available_at"] <= t],
            carbon=self.carbon[self.carbon["available_at"] <= t],
        )


def empty_carbon() -> pd.DataFrame:
    return pd.DataFrame(
        {
            "valid_from": pd.Series(dtype="datetime64[us, UTC]"),
            "carbon_gco2_per_kwh": pd.Series(dtype="float64"),
            "available_at": pd.Series(dtype="datetime64[us, UTC]"),
        }
    )


def load_sources(db: Database, start: dt.datetime, end: dt.datetime) -> Sources:
    """Inputs for ``valid_from`` in ``[start, end)``."""
    indo = db.read_frame(
        """
        SELECT valid_from, valid_to, demand_mw FROM demand
         WHERE series = 'indo' AND valid_from >= %(start)s AND valid_from < %(end)s
         ORDER BY valid_from
        """,
        {"start": start, "end": end},
    )
    indo["available_at"] = indo.pop("valid_to") + INDO_PUBLICATION_LAG

    weather = db.read_frame(
        """
        SELECT valid_from, series AS location, issued_at, temperature_2m_c, wind_speed_10m_ms, wind_speed_100m_ms,
               shortwave_radiation_wm2, cloud_cover_pct
          FROM weather
         WHERE issued_at IS NOT NULL AND valid_from >= %(start)s AND valid_from < %(end)s
         ORDER BY valid_from, location, issued_at
        """,
        {"start": start, "end": end},
    )
    weather["available_at"] = weather["issued_at"]

    carbon = load_carbon_actuals(db, start, end).rename("carbon_gco2_per_kwh").reset_index()
    carbon["available_at"] = carbon["valid_from"] + pd.Timedelta(minutes=30) + CARBON_PUBLICATION_LAG
    return Sources(indo=indo, weather=weather, carbon=carbon)


def load_targets(db: Database, start: dt.datetime, end: dt.datetime) -> pd.Series:
    """National demand actuals (settled ND, else INDO; ADR-0010), indexed by ``valid_from``."""
    frame = db.read_frame(
        """
        SELECT valid_from, demand_mw FROM national_demand_actual
         WHERE valid_from >= %(start)s AND valid_from < %(end)s
         ORDER BY valid_from
        """,
        {"start": start, "end": end},
    )
    return frame.set_index("valid_from")["demand_mw"].astype("float64")


def load_neso_day_ahead(db: Database, start: dt.datetime, end: dt.datetime) -> pd.Series:
    """NESO's day-ahead demand forecast as published by 13:00 UK on D-1 (ADR-0010), indexed by ``valid_from``.

    The benchmark only: never a model input (ADR-0011).
    """
    frame = db.read_frame(
        """
        SELECT valid_from, forecast_mw FROM ndf_day_ahead
         WHERE valid_from >= %(start)s AND valid_from < %(end)s
         ORDER BY valid_from
        """,
        {"start": start, "end": end},
    )
    return frame.set_index("valid_from")["forecast_mw"].astype("float64")


def load_carbon_actuals(db: Database, start: dt.datetime, end: dt.datetime) -> pd.Series:
    """National (GB) carbon intensity actuals in gCO2/kWh, indexed by ``valid_from``.

    A zero is treated as missing: GB's grid never runs at zero carbon, and MAPE can't divide by it. It has happened
    once in two years (2026-08-06 11:00Z), as a glitch in the source.
    """
    frame = db.read_frame(
        """
        SELECT valid_from, actual_gco2_per_kwh FROM carbon_intensity
         WHERE region = 'GB' AND issued_at IS NULL AND actual_gco2_per_kwh > 0
           AND valid_from >= %(start)s AND valid_from < %(end)s
         ORDER BY valid_from
        """,
        {"start": start, "end": end},
    )
    return frame.set_index("valid_from")["actual_gco2_per_kwh"].astype("float64")


def load_neso_carbon_final(db: Database, start: dt.datetime, end: dt.datetime) -> pd.Series:
    """NESO's *final* national carbon intensity forecast for each half-hour, indexed by ``valid_from``.

    Not a day-ahead forecast: it is the last one NESO made, shortly before the half-hour, so it knew far more than a
    day-ahead model can. Its day-ahead revisions are only recorded from late October 2026 (ADR-0011), too few for a
    backtest. Reports label this benchmark accordingly.
    """
    frame = db.read_frame(
        """
        SELECT valid_from, forecast_gco2_per_kwh FROM carbon_intensity
         WHERE region = 'GB' AND issued_at IS NULL AND forecast_gco2_per_kwh IS NOT NULL
           AND valid_from >= %(start)s AND valid_from < %(end)s
         ORDER BY valid_from
        """,
        {"start": start, "end": end},
    )
    return frame.set_index("valid_from")["forecast_gco2_per_kwh"].astype("float64")
