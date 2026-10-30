from __future__ import annotations

import datetime as dt

import numpy as np
import pandas as pd
import pytest
import synthetic

from gridpulse_forecasting.dataset import build_day_ahead, day_ahead_issue_time, london_day
from gridpulse_forecasting.features import FEATURES, build_features, calendar_features, same_wall_time

GMT_DAY = dt.date(2025, 3, 20)  # issue time 2025-03-19 13:00Z


@pytest.fixture(scope="module")
def examples() -> pd.DataFrame:
    source = synthetic.sources()
    return build_day_ahead(source, synthetic.targets(source), [GMT_DAY]).set_index("target_time")


def at(examples: pd.DataFrame, utc: str) -> pd.Series:
    return examples.loc[pd.Timestamp(utc)]


# ------------------------------------------------------------------ issue times and target days


@pytest.mark.parametrize(
    ("day", "issue_utc"),
    [
        (dt.date(2025, 1, 15), "2025-01-14T13:00Z"),  # GMT: 13:00 UK is 13:00 UTC
        (dt.date(2025, 6, 15), "2025-06-14T12:00Z"),  # BST: 13:00 UK is 12:00 UTC
        (dt.date(2025, 3, 31), "2025-03-30T12:00Z"),  # the day after the spring change: cut-off is already BST
    ],
)
def test_day_ahead_issue_time_is_13_00_uk_the_day_before(day: dt.date, issue_utc: str) -> None:
    assert day_ahead_issue_time(day) == pd.Timestamp(issue_utc)


@pytest.mark.parametrize(
    ("day", "periods", "first_utc"),
    [
        (dt.date(2025, 3, 20), 48, "2025-03-20T00:00Z"),
        (dt.date(2025, 3, 30), 46, "2025-03-30T00:00Z"),  # clocks go forward
        (dt.date(2025, 10, 26), 50, "2025-10-25T23:00Z"),  # clocks go back; the day starts at midnight BST
    ],
)
def test_a_uk_day_has_46_48_or_50_half_hours(day: dt.date, periods: int, first_utc: str) -> None:
    index = london_day(day)
    assert len(index) == periods
    assert index[0] == pd.Timestamp(first_utc)


def test_one_row_per_target_half_hour_with_every_feature(examples: pd.DataFrame) -> None:
    assert len(examples) == 48
    assert set(FEATURES) <= set(examples.columns)
    assert (examples["issue_time"] == pd.Timestamp("2025-03-19T13:00Z")).all()


def test_target_is_nan_where_the_actual_is_not_known() -> None:
    source = synthetic.sources()
    beyond_the_data = dt.date(2025, 4, 20)

    examples = build_day_ahead(source, synthetic.targets(source), [beyond_the_data])

    assert examples["target_mw"].isna().all()


# ------------------------------------------------------------------ calendar


def test_lead_hours_count_from_the_issue_time(examples: pd.DataFrame) -> None:
    assert examples["lead_hours"].iloc[0] == 11.0
    assert examples["lead_hours"].iloc[-1] == 34.5


def test_calendar_uses_uk_local_time() -> None:
    # 23:30 UTC on 4 May 2025 is 00:30 BST on Monday 5 May, the Early May bank holiday.
    features = calendar_features(pd.DatetimeIndex([pd.Timestamp("2025-05-04T23:30Z")]))
    row = features.iloc[0]
    assert (row["half_hour_of_day"], row["day_of_week"], row["is_bank_holiday"]) == (1, 0, True)
    assert not row["is_weekend"]


def test_christmas_period_spans_christmas_eve_to_2_january() -> None:
    days = pd.DatetimeIndex(pd.to_datetime(["2025-12-23", "2025-12-24", "2026-01-02", "2026-01-03"]).tz_localize("UTC"))
    features = calendar_features(days + pd.Timedelta(hours=12))
    assert features["is_christmas_period"].tolist() == [False, True, True, False]


# ------------------------------------------------------------------ demand


def test_same_wall_time_follows_uk_clocks_across_a_clock_change() -> None:
    # 00:00 BST on 2 April is 23:00Z on 1 April; a week earlier, 00:00 GMT on 26 March, is 00:00Z.
    assert same_wall_time(pd.DatetimeIndex([pd.Timestamp("2025-04-01T23:00Z")]), 7)[0] == pd.Timestamp(
        "2025-03-26T00:00Z"
    )


def test_demand_lags_take_the_same_uk_clock_time(examples: pd.DataFrame) -> None:
    row = at(examples, "2025-03-20T18:00Z")
    actual_indo = row["target_mw"] - 7.0  # synthetic actuals are INDO + 7
    # One synthetic MW per half-hour: 7 days of GMT is 336 half-hours earlier, 2 days is 96, 14 days is 672.
    assert (row["demand_lag_2d"], row["demand_lag_7d"], row["demand_lag_14d"]) == (
        actual_indo - 96,
        actual_indo - 336,
        actual_indo - 672,
    )


def test_latest_demand_is_the_last_period_published_by_the_issue_time(examples: pd.DataFrame) -> None:
    # Published an hour after it ends: the 11:30-12:00 period is out at 13:00, the 12:00 one isn't.
    last_known = synthetic.sources().indo.set_index("valid_from").loc[pd.Timestamp("2025-03-19T11:30Z"), "demand_mw"]
    assert (examples["demand_latest"] == last_known).all()


def test_lags_not_yet_published_are_missing_not_guessed() -> None:
    source = synthetic.sources()
    targets = london_day(GMT_DAY)

    # Issued at 06:00 on the 18th: the 2-day lag for the evening of the 20th (the evening of the 18th) is unknown.
    issue_time = pd.Timestamp("2025-03-18T06:00Z")
    features = build_features(source.as_of(issue_time), issue_time, targets)

    assert features["demand_lag_2d"].isna().sum() > 0
    assert features["demand_lag_7d"].notna().all()


# ------------------------------------------------------------------ weather


def test_weather_uses_the_latest_revision_issued_by_the_cut_off(examples: pd.DataFrame) -> None:
    # Synthetic revisions: 24 h ahead forecasts 12.4 C, 48 h ahead 14.8 C. The 24 h revision for 18:00Z on the
    # 20th is issued at 18:00Z on the 19th, after the 13:00 cut-off, so the evening falls back to the 48 h one.
    assert at(examples, "2025-03-20T17:30Z")["temperature_c"] == pytest.approx(12.4)
    assert at(examples, "2025-03-20T18:00Z")["temperature_c"] == pytest.approx(14.8)
    assert at(examples, "2025-03-20T17:30Z")["weather_forecast_age_hours"] == pytest.approx(29.0)  # issued 12:00Z
    assert at(examples, "2025-03-20T18:00Z")["weather_forecast_age_hours"] == pytest.approx(48.0)


def test_heating_degrees_measure_how_far_below_15_5_c(examples: pd.DataFrame) -> None:
    assert at(examples, "2025-03-20T12:00Z")["heating_degrees"] == pytest.approx(15.5 - 12.4)


def test_radiation_for_an_hour_comes_from_the_value_stamped_at_its_end(examples: pd.DataFrame) -> None:
    # Synthetic radiation equals the UTC hour of its timestamp; the hour 10:00-11:00 is stamped 11:00.
    assert at(examples, "2025-03-20T10:00Z")["solar_radiation_wm2"] == 11.0
    assert at(examples, "2025-03-20T10:30Z")["solar_radiation_wm2"] == 11.0


def test_a_missing_location_makes_national_weather_missing() -> None:
    source = synthetic.sources()
    without_aberdeen = source.weather[source.weather["location"] != "aberdeen"]
    partial = type(source)(indo=source.indo, weather=without_aberdeen)

    examples = build_day_ahead(partial, synthetic.targets(source), [GMT_DAY])

    assert examples["temperature_c"].isna().all()
    assert np.isnan(examples["solar_radiation_wm2"]).all()
