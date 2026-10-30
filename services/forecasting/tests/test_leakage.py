"""The guarantee everything in Phase 2 rests on: features never depend on data that arrived after the issue time.

If a feature used anything published later, scrambling that later data would change it. These tests scramble
everything after the issue time and require identical features (ADR-0011).
"""

from __future__ import annotations

import datetime as dt

import pandas as pd
import pytest
import synthetic

from gridpulse_forecasting.dataset import build_day_ahead, build_rolling, day_ahead_issue_time
from gridpulse_forecasting.features import FEATURES

# An ordinary day, the spring clock change (46 periods) and a day straddling it for the 7-day lag.
DAYS = [dt.date(2025, 3, 20), dt.date(2025, 3, 30), dt.date(2025, 4, 2)]


@pytest.mark.parametrize("day", DAYS, ids=str)
def test_features_ignore_everything_published_after_the_issue_time(day: dt.date) -> None:
    source = synthetic.sources()
    targets = synthetic.targets(source)

    honest = build_day_ahead(source, targets, [day])
    scrambled = build_day_ahead(synthetic.scramble_after(source, day_ahead_issue_time(day)), targets, [day])

    pd.testing.assert_frame_equal(honest[FEATURES], scrambled[FEATURES])


@pytest.mark.parametrize("day", DAYS, ids=str)
def test_scrambling_before_the_issue_time_does_change_features(day: dt.date) -> None:
    # The control: if scrambling never changed anything, the test above would pass for the wrong reason.
    source = synthetic.sources()
    targets = synthetic.targets(source)
    a_week_earlier = day_ahead_issue_time(day) - pd.Timedelta(days=7)

    honest = build_day_ahead(source, targets, [day])
    scrambled = build_day_ahead(synthetic.scramble_after(source, a_week_earlier), targets, [day])

    changed = [f for f in FEATURES if not honest[f].equals(scrambled[f])]
    assert {"demand_lag_2d", "demand_latest", "demand_mean_24h", "temperature_c", "carbon_latest"} <= set(changed)


def test_targets_are_labels_not_inputs() -> None:
    source = synthetic.sources()
    day = DAYS[0]

    honest = build_day_ahead(source, synthetic.targets(source), [day])
    shifted = build_day_ahead(source, synthetic.targets(source) + 1_000, [day])

    pd.testing.assert_frame_equal(honest[FEATURES], shifted[FEATURES])
    assert (shifted["target"] - honest["target"] == 1_000).all()


# Rolling issue times, including the shortest leads: a forecast for the very next half-hour.
ROLLING_ISSUES = ["2025-03-19T07:00Z", "2025-03-29T19:00Z", "2025-04-02T00:30Z"]


@pytest.mark.parametrize("issue", ROLLING_ISSUES)
def test_rolling_features_ignore_everything_published_after_the_issue_time(issue: str) -> None:
    source = synthetic.sources()
    targets = synthetic.targets(source)
    t = pd.Timestamp(issue)

    honest = build_rolling(source, targets, [t])
    scrambled = build_rolling(synthetic.scramble_after(source, t), targets, [t])

    pd.testing.assert_frame_equal(honest[FEATURES], scrambled[FEATURES])
