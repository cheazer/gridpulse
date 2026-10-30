"""Features for forecasting national demand at target half-hours, from a snapshot of what was known at issue time.

Functions here take a ``Sources`` snapshot (already cut at the issue time) and never load data themselves, so a
feature can only be as good as information available then (ADR-0011). Times are UTC; anything people do by the
clock (time of day, weekdays, holidays, "same time last week") is computed in UK local time.
"""

from __future__ import annotations

import datetime as dt
from functools import cache

import holidays
import numpy as np
import pandas as pd

from gridpulse_forecasting.sources import Sources

LONDON = "Europe/London"

# Population, in millions (ONS mid-year estimates, rounded), of the Carbon Intensity region each weather location
# stands for (ADR-0008). Demand follows people, so national temperature is a population-weighted mean. Only the
# relative sizes matter.
POPULATION_WEIGHTS = {
    "london": 8.9,
    "birmingham": 6.0,
    "manchester": 7.5,
    "leeds": 5.6,
    "edinburgh": 3.9,
    "cardiff": 2.3,
    "norwich": 6.4,
    "aberdeen": 1.5,
}

# Below this outdoor temperature buildings need heating: the UK convention for heating degree days.
HEATING_BASE_C = 15.5

LAG_DAYS = (2, 7, 14)

FEATURES = [
    "lead_hours",
    "half_hour_of_day",
    "day_of_week",
    "is_weekend",
    "is_bank_holiday",
    "is_christmas_period",
    "day_of_year_sin",
    "day_of_year_cos",
    *(f"demand_lag_{days}d" for days in LAG_DAYS),
    "demand_latest",
    "demand_mean_24h",
    "temperature_c",
    "heating_degrees",
    "wind_speed_100m_ms",
    "solar_radiation_wm2",
    "cloud_cover_pct",
    "weather_forecast_age_hours",
]


def build_features(snapshot: Sources, issue_time: pd.Timestamp, targets: pd.DatetimeIndex) -> pd.DataFrame:
    """One row per target half-hour, columns as FEATURES; NaN where the snapshot doesn't have the input."""
    frame = pd.DataFrame(index=targets)
    frame["lead_hours"] = (targets - issue_time) / pd.Timedelta(hours=1)
    frame = frame.join(calendar_features(targets))
    frame = frame.join(demand_features(snapshot.indo, issue_time, targets))
    frame = frame.join(weather_features(snapshot.weather, targets))
    return frame[FEATURES]


def calendar_features(targets: pd.DatetimeIndex) -> pd.DataFrame:
    local = targets.tz_convert(LONDON)
    day_of_year = 2 * np.pi * (local.day_of_year - 1) / 365.25
    dates = local.date
    return pd.DataFrame(
        {
            # Local half-hour index (0-47) rather than settlement period, which shifts on clock-change days.
            "half_hour_of_day": local.hour * 2 + local.minute // 30,
            "day_of_week": local.day_of_week,
            "is_weekend": local.day_of_week >= 5,
            "is_bank_holiday": [is_bank_holiday(d) for d in dates],
            # Between Christmas Eve and 2 January demand behaves like neither a weekday nor a weekend.
            "is_christmas_period": [(d.month == 12 and d.day >= 24) or (d.month == 1 and d.day <= 2) for d in dates],
            "day_of_year_sin": np.sin(day_of_year),
            "day_of_year_cos": np.cos(day_of_year),
        },
        index=targets,
    )


def is_bank_holiday(day: dt.date) -> bool:
    # England and Wales: most of GB demand. Scotland's few differing days are a known, small error.
    return day in _bank_holidays(day.year)


@cache
def _bank_holidays(year: int) -> holidays.HolidayBase:
    return holidays.country_holidays("GB", subdiv="ENG", years=year)


def demand_features(indo: pd.DataFrame, issue_time: pd.Timestamp, targets: pd.DatetimeIndex) -> pd.DataFrame:
    demand = indo.set_index("valid_from")["demand_mw"]
    frame = pd.DataFrame(index=targets)
    for days in LAG_DAYS:
        frame[f"demand_lag_{days}d"] = demand.reindex(same_wall_time(targets, days)).to_numpy()
    recent = demand[demand.index >= issue_time - pd.Timedelta(hours=24)]
    frame["demand_latest"] = demand.iloc[-1] if len(demand) else np.nan
    frame["demand_mean_24h"] = recent.mean() if len(recent) else np.nan
    return frame


def same_wall_time(targets: pd.DatetimeIndex, days: int) -> pd.DatetimeIndex:
    """The same UK clock time ``days`` earlier, in UTC: across a clock change that is 23 or 25 hours per day."""
    wall = targets.tz_convert(LONDON).tz_localize(None) - pd.Timedelta(days=days)
    # The repeated autumn hour takes its first (BST) occurrence; the skipped spring hour moves forward.
    first_occurrence = np.ones(len(wall), dtype=bool)
    return wall.tz_localize(LONDON, ambiguous=first_occurrence, nonexistent="shift_forward").tz_convert("UTC")


def weather_features(weather: pd.DataFrame, targets: pd.DatetimeIndex) -> pd.DataFrame:
    """National weather for each target half-hour, from the latest forecast revision in the snapshot."""
    hours = targets.floor("h")
    window = weather[
        (weather["valid_from"] >= hours.min()) & (weather["valid_from"] <= hours.max() + pd.Timedelta(hours=1))
    ]
    latest = window.sort_values("issued_at").drop_duplicates(["location", "valid_from"], keep="last")
    latest = latest.assign(age_hours=(latest["valid_from"] - latest["issued_at"]).dt.total_seconds() / 3600)

    def by_location(column: str, hours_later: int = 0) -> pd.DataFrame:
        """Hour x location table of one variable, taking each hour's value from the row stamped hours_later."""
        table = latest.pivot(index="valid_from", columns="location", values=column)
        table.index = pd.DatetimeIndex(table.index) - pd.Timedelta(hours=hours_later)
        return table.reindex(index=hours, columns=list(POPULATION_WEIGHTS))

    weights = pd.Series(POPULATION_WEIGHTS)
    temperature = weighted_mean(by_location("temperature_2m_c"), weights)
    # Open-Meteo's radiation at t is the mean over the hour *ending* at t (ADR-0008), so the hour starting at h
    # takes the value stamped h + 1h.
    radiation = weighted_mean(by_location("shortwave_radiation_wm2", hours_later=1), weights)

    return pd.DataFrame(
        {
            "temperature_c": temperature,
            "heating_degrees": np.maximum(HEATING_BASE_C - temperature, 0),
            "wind_speed_100m_ms": by_location("wind_speed_100m_ms").mean(axis=1, skipna=False).to_numpy(),
            "solar_radiation_wm2": radiation,
            "cloud_cover_pct": by_location("cloud_cover_pct").mean(axis=1, skipna=False).to_numpy(),
            "weather_forecast_age_hours": by_location("age_hours").mean(axis=1, skipna=False).to_numpy(),
        },
        index=targets,
    )


def weighted_mean(table: pd.DataFrame, weights: pd.Series) -> np.ndarray:
    """Row-wise weighted mean; NaN if any location is missing, rather than a mean skewed to the ones present."""
    values = table.to_numpy(dtype="float64")
    return np.asarray(values @ weights.reindex(table.columns).to_numpy() / weights.sum())
