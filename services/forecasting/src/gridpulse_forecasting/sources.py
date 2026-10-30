"""Model inputs, each row stamped with when it became known, and the targets the model learns.

Every input row carries an ``available_at`` timestamp. ``Sources.as_of(t)`` drops every row not yet available at
``t``, and feature code only ever receives that snapshot, so it cannot see the future however it is written
(ADR-0011). The targets are kept apart: they are labels, never inputs.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass

import pandas as pd

from gridpulse_forecasting.db import Database

# Elexon publishes INDO for a settlement period within minutes of it ending. One hour is a conservative
# allowance: assuming data arrives later than it does can only make backtests pessimistic, never optimistic.
INDO_PUBLICATION_LAG = pd.Timedelta(hours=1)

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

    def as_of(self, t: pd.Timestamp) -> Sources:
        """Only what was available at ``t``."""
        return Sources(
            indo=self.indo[self.indo["available_at"] <= t],
            weather=self.weather[self.weather["available_at"] <= t],
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
    return Sources(indo=indo, weather=weather)


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
