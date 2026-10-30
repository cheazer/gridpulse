"""Training and evaluation examples: one row per (issue time, target half-hour), features as of the issue time."""

from __future__ import annotations

import datetime as dt
from collections.abc import Iterable

import pandas as pd

from gridpulse_forecasting.features import LONDON, build_features
from gridpulse_forecasting.sources import Sources

# NESO's day-ahead forecast is the one published by 13:00 UK time the day before (ADR-0010); ours gets the same
# information cut-off, so the comparison is fair.
DAY_AHEAD_CUTOFF = dt.time(13, 0)


def day_ahead_issue_time(day: dt.date) -> pd.Timestamp:
    """13:00 UK time on the day before ``day``, in UTC."""
    local = pd.Timestamp(dt.datetime.combine(day - dt.timedelta(days=1), DAY_AHEAD_CUTOFF)).tz_localize(LONDON)
    return local.tz_convert("UTC")


def london_day(day: dt.date) -> pd.DatetimeIndex:
    """Every half-hour of a UK day, in UTC: 46, 48 or 50 of them."""
    start = pd.Timestamp(day).tz_localize(LONDON).tz_convert("UTC")
    end = pd.Timestamp(day + dt.timedelta(days=1)).tz_localize(LONDON).tz_convert("UTC")
    return pd.date_range(start, end, freq="30min", inclusive="left", name="target_time")


def build_day_ahead(sources: Sources, targets: pd.Series, days: Iterable[dt.date]) -> pd.DataFrame:
    """Day-ahead examples for each UK day in ``days``: features as of 13:00 the day before, and the actual demand.

    ``target_mw`` is NaN where the actual isn't known yet, so the same function builds rows to predict.
    """
    parts = []
    for day in days:
        issue_time = day_ahead_issue_time(day)
        periods = london_day(day)
        part = build_features(sources.as_of(issue_time), issue_time, periods)
        part.insert(0, "issue_time", issue_time)
        part["target_mw"] = targets.reindex(periods).to_numpy()
        parts.append(part)
    if not parts:
        return pd.DataFrame()
    return pd.concat(parts).reset_index()
