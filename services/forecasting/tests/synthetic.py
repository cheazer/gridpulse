"""Small synthetic inputs with the same shape and availability rules as the real ones."""

from __future__ import annotations

import numpy as np
import pandas as pd

from gridpulse_forecasting.features import POPULATION_WEIGHTS
from gridpulse_forecasting.sources import CARBON_PUBLICATION_LAG, INDO_PUBLICATION_LAG, WEATHER_COLUMNS, Sources

# Hours before the forecast hour at which each synthetic weather revision is issued (6-hour buckets, as stored).
REVISION_LEADS = (24, 48)


def sources(start: str = "2025-03-01", end: str = "2025-04-15") -> Sources:
    periods = pd.date_range(start, end, freq="30min", tz="UTC", inclusive="left")
    # Distinct, recognisable values: demand in MW is 20,000 plus the period's index.
    indo = pd.DataFrame(
        {
            "valid_from": periods,
            "demand_mw": 20_000.0 + np.arange(len(periods)),
            "available_at": periods + pd.Timedelta(minutes=30) + INDO_PUBLICATION_LAG,
        }
    )

    hours = pd.date_range(start, end, freq="h", tz="UTC", inclusive="left")
    rows = []
    for lead in REVISION_LEADS:
        issued = (hours - pd.Timedelta(hours=lead)).floor("6h")
        for location in POPULATION_WEIGHTS:
            frame = pd.DataFrame({"valid_from": hours, "location": location, "issued_at": issued})
            # Each revision forecasts a different temperature, so tests can tell which one a feature used.
            frame["temperature_2m_c"] = 10.0 + lead / 10
            frame["wind_speed_10m_ms"] = 5.0
            frame["wind_speed_100m_ms"] = 8.0
            # Radiation is the hour's UTC hour number, so tests can check the hour-ending shift.
            frame["shortwave_radiation_wm2"] = hours.hour.astype("float64")
            frame["cloud_cover_pct"] = 50.0
            rows.append(frame)
    weather = pd.concat(rows, ignore_index=True)
    weather["available_at"] = weather["issued_at"]
    assert set(WEATHER_COLUMNS) <= set(weather.columns)

    # Carbon intensity: 100 g/kWh plus the half-hour's index modulo 100, so lags are recognisable too.
    carbon = pd.DataFrame(
        {
            "valid_from": periods,
            "carbon_gco2_per_kwh": 100.0 + np.arange(len(periods)) % 100,
            "available_at": periods + pd.Timedelta(minutes=30) + CARBON_PUBLICATION_LAG,
        }
    )
    return Sources(indo=indo, weather=weather, carbon=carbon)


def targets(source: Sources) -> pd.Series:
    """Actual demand: INDO plus a small offset, as settled ND differs slightly from INDO."""
    return source.indo.set_index("valid_from")["demand_mw"].rename_axis("valid_from") + 7.0


def scramble_after(source: Sources, t: pd.Timestamp) -> Sources:
    """The same inputs, except every value that became available after ``t`` is replaced with nonsense."""
    indo = source.indo.copy()
    indo.loc[indo["available_at"] > t, "demand_mw"] = -1.0
    weather = source.weather.copy()
    later = weather["available_at"] > t
    for column in WEATHER_COLUMNS:
        weather.loc[later, column] = 999.0
    carbon = source.carbon.copy()
    carbon.loc[carbon["available_at"] > t, "carbon_gco2_per_kwh"] = -1.0
    return Sources(indo=indo, weather=weather, carbon=carbon)
