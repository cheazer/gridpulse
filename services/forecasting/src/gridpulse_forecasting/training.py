"""Retraining with a champion/challenger gate (ADR-0011, step 2.7).

The question a retrain must answer is "is the new model at least as good as the one in production?", and the
answer is only fair on data neither model has seen. The champion was trained on data up to its cutoff C. So:

1. The challenger (the current code and configuration) is trained on the same data, up to C.
2. Both forecast everything issued since C, which neither saw, and are scored on it. The only difference between
   them is the code or configuration, so this catches regressions; unchanged code scores identically.
3. Absolute gates apply too: clearly better than seasonal naive, and P10-P90 coverage near 80%.
4. If it passes, a model trained on *all* data up to now is registered and becomes champion, so a routine retrain
   promotes the same method with fresher data.

With no champion yet, or less than a week since its cutoff (too little evidence), only the absolute gates apply, on
a held-out last two weeks.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field

import pandas as pd

from gridpulse_forecasting.backtest import training_rows
from gridpulse_forecasting.dataset import HORIZON, build_rolling, rolling_issue_times
from gridpulse_forecasting.db import Database
from gridpulse_forecasting.features import FEATURES, LAG_DAYS, LONDON
from gridpulse_forecasting.metrics import interval_coverage, point_metrics
from gridpulse_forecasting.models import Forecaster
from gridpulse_forecasting.sources import Sources, load_sources
from gridpulse_forecasting.tasks import Task

# A challenger may be at most this much worse than the champion: identical code gives identical scores, and the
# tolerance stops noise in the last decimal place from blocking a retrain on fresher data.
TOLERANCE = 0.01
# Less than this since the champion's cutoff is too little unseen data to compare on.
MIN_COMPARISON = pd.Timedelta(days=7)
# Without a fair comparison, the absolute gates are checked on this much held-out recent data.
HOLDOUT = pd.Timedelta(days=14)
# The model's MAE must be at most this share of seasonal naive's (it achieves about 0.5 for demand, 0.45 for carbon).
MAX_NAIVE_RATIO = 0.75
# P10-P90 should hold about 80% of actuals; outside this range the intervals are miscalibrated.
COVERAGE_RANGE = (0.65, 0.95)


@dataclass(frozen=True)
class Scores:
    mae: float
    naive_mae: float
    coverage: float
    forecasts: int


@dataclass(frozen=True)
class Decision:
    promote: bool
    reasons: list[str] = field(default_factory=list)


def decide(challenger: Scores, champion_mae: float | None) -> Decision:
    """Promote only if every gate passes. Reasons are recorded either way, for the registry and the logs."""
    reasons = []
    ok = True

    ratio = challenger.mae / challenger.naive_mae
    if ratio > MAX_NAIVE_RATIO:
        ok = False
        reasons.append(f"FAIL MAE is {ratio:.0%} of seasonal naive's (limit {MAX_NAIVE_RATIO:.0%})")
    else:
        reasons.append(f"pass MAE is {ratio:.0%} of seasonal naive's")

    low, high = COVERAGE_RANGE
    if not low <= challenger.coverage <= high:
        ok = False
        reasons.append(f"FAIL P10-P90 coverage {challenger.coverage:.0%} outside {low:.0%}-{high:.0%}")
    else:
        reasons.append(f"pass P10-P90 coverage {challenger.coverage:.0%}")

    if champion_mae is None:
        reasons.append("no fair comparison with a champion (none, or too little unseen data)")
    elif challenger.mae > champion_mae * (1 + TOLERANCE):
        ok = False
        reasons.append(f"FAIL MAE {challenger.mae:,.2f} worse than the champion's {champion_mae:,.2f}")
    else:
        reasons.append(f"pass MAE {challenger.mae:,.2f} against the champion's {champion_mae:,.2f}")
    return Decision(ok, reasons)


def fit_rows(examples: pd.DataFrame, cutoff: pd.Timestamp) -> pd.DataFrame:
    """What a model trained at ``cutoff`` learns from: examples issued on earlier UK days, actuals published by then.

    One definition for the champion, the challenger and the forecast job, so "trained up to C" means the same thing.
    """
    issued_before = examples["issue_time"].dt.tz_convert(LONDON).dt.date < cutoff.tz_convert(LONDON).date()
    return training_rows(examples[issued_before], cutoff)


def evaluation_rows(examples: pd.DataFrame, since: pd.Timestamp, now: pd.Timestamp) -> pd.DataFrame:
    """Forecasts issued since ``since`` with actuals known by ``now``: unseen by a model trained up to ``since``."""
    known = training_rows(examples, now)
    return known[known["issue_time"] >= since]


def score(model: Forecaster, rows: pd.DataFrame, naive_feature: str) -> Scores:
    forecast = model.predict(rows[FEATURES])
    usable = rows[naive_feature].notna()
    actual = rows.loc[usable, "target"]
    return Scores(
        mae=point_metrics(actual, forecast.loc[usable, "p50"])["mae"],
        naive_mae=point_metrics(actual, rows.loc[usable, naive_feature])["mae"],
        coverage=interval_coverage(actual, forecast.loc[usable, "p10"], forecast.loc[usable, "p90"]),
        forecasts=int(usable.sum()),
    )


def load_rolling(db: Database, task: Task, now: pd.Timestamp, first_day: dt.date) -> tuple[Sources, pd.DataFrame]:
    """Inputs up to ``now`` and the rolling examples issued on every UK day before today."""
    start = dt.datetime.combine(first_day - dt.timedelta(days=max(LAG_DAYS) + 1), dt.time(), dt.UTC)
    end = (now + HORIZON + pd.Timedelta(days=1)).to_pydatetime()
    sources = load_sources(db, start, end)
    targets = task.load_targets(db, start, now.to_pydatetime())
    today = now.tz_convert(LONDON).date()
    return sources, build_rolling(sources, targets, rolling_issue_times(first_day, today - dt.timedelta(days=1)))


@dataclass(frozen=True)
class Outcome:
    decision: Decision
    challenger: Scores
    champion_mae: float | None
    evaluated_since: pd.Timestamp
    model: Forecaster


def challenge(
    task: Task,
    examples: pd.DataFrame,
    now: pd.Timestamp,
    champion: tuple[Forecaster, pd.Timestamp] | None,
) -> Outcome:
    """Run the gate, then fit the model to register on everything known at ``now``."""
    fair = champion is not None and now - champion[1] >= MIN_COMPARISON
    since = champion[1] if fair and champion else now - HOLDOUT
    unseen = evaluation_rows(examples, since, now)

    challenger = task.production()
    train = fit_rows(examples, since)
    challenger.fit(train[FEATURES], train["target"])
    scores = score(challenger, unseen, task.naive_feature)
    champion_mae = score(champion[0], unseen, task.naive_feature).mae if fair and champion else None

    final = task.production()
    everything = fit_rows(examples, now)
    final.fit(everything[FEATURES], everything["target"])
    return Outcome(decide(scores, champion_mae), scores, champion_mae, since, final)
