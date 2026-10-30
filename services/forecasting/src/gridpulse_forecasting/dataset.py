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


# The rolling forecast looks 48 hours ahead (96 half-hours) from whenever it is issued (ADR-0011).
HORIZON = pd.Timedelta(hours=48)

# Training issue times for the rolling model: every 6 hours on the UK clock, including the 13:00 day-ahead cut-off,
# so the model learns every lead time from 0 to 48 hours and is trained on the day-ahead case it is judged on.
ROLLING_ISSUE_HOURS = (1, 7, 13, 19)


def build_day_ahead(sources: Sources, targets: pd.Series, days: Iterable[dt.date]) -> pd.DataFrame:
    """Day-ahead examples for each UK day in ``days``: features as of 13:00 the day before, and the actual.

    ``target`` is NaN where the actual isn't known yet, so the same function builds rows to predict.
    """
    return build_examples(sources, targets, ((day_ahead_issue_time(day), london_day(day)) for day in days))


def build_rolling(sources: Sources, targets: pd.Series, issue_times: Iterable[pd.Timestamp]) -> pd.DataFrame:
    """Rolling examples: from each issue time, every half-hour of the next 48 hours."""
    return build_examples(sources, targets, ((t, rolling_targets(t)) for t in issue_times))


def rolling_targets(issue_time: pd.Timestamp) -> pd.DatetimeIndex:
    """The 96 half-hours starting at the first boundary at or after ``issue_time``."""
    start = issue_time.ceil("30min")
    return pd.date_range(start, start + HORIZON, freq="30min", inclusive="left", name="target_time")


def rolling_issue_times(first: dt.date, last: dt.date) -> list[pd.Timestamp]:
    """Training issue times for UK days first..last, at ROLLING_ISSUE_HOURS UK time, in UTC.

    01:00 doesn't exist on the spring clock-change day (clocks jump to 02:00), so it moves to 02:00 BST; on the
    autumn day 01:00 happens twice, and the first (BST) one is used.
    """
    days = pd.date_range(first, last, freq="D")
    return [
        pd.Timestamp(dt.datetime.combine(day.date(), dt.time(hour)))
        .tz_localize(LONDON, ambiguous=True, nonexistent="shift_forward")
        .tz_convert("UTC")
        for day in days
        for hour in ROLLING_ISSUE_HOURS
    ]


def build_examples(
    sources: Sources, targets: pd.Series, issues: Iterable[tuple[pd.Timestamp, pd.DatetimeIndex]]
) -> pd.DataFrame:
    parts = []
    for issue_time, periods in issues:
        part = build_features(sources.as_of(issue_time), issue_time, periods)
        part.insert(0, "issue_time", issue_time)
        part["target"] = targets.reindex(periods).to_numpy()
        parts.append(part)
    if not parts:
        return pd.DataFrame()
    return pd.concat(parts).reset_index()
