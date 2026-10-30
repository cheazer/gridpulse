from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from gridpulse_forecasting.features import FEATURES
from gridpulse_forecasting.metrics import interval_coverage, point_metrics
from gridpulse_forecasting.models import ConformalisedLightGBM, LightGBMQuantiles, SeasonalNaive, conformal_margin


def synthetic(rows: int, seed: int) -> tuple[pd.DataFrame, pd.Series]:
    """Demand driven by time of day and temperature, with noise of known spread (sd 400 MW)."""
    rng = np.random.default_rng(seed)
    features = pd.DataFrame(0.0, index=range(rows), columns=FEATURES)
    features["half_hour_of_day"] = rng.integers(0, 48, rows)
    features["temperature_c"] = rng.uniform(-2, 25, rows)
    features["is_weekend"] = rng.random(rows) < 2 / 7
    demand = (
        25_000
        + 6_000 * np.sin(features["half_hour_of_day"] / 48 * 2 * np.pi)
        - 300 * features["temperature_c"]
        - 3_000 * features["is_weekend"]
    )
    features["demand_lag_7d"] = demand + rng.normal(0, 1_500, rows)  # a weak, noisy hint
    return features, demand + rng.normal(0, 400, rows)


@pytest.fixture(scope="module")
def fitted() -> LightGBMQuantiles:
    model = LightGBMQuantiles()
    model.fit(*synthetic(8_000, seed=1))
    return model


def test_learns_the_signal_far_better_than_seasonal_naive(fitted: LightGBMQuantiles) -> None:
    features, actual = synthetic(4_000, seed=2)

    model_mae = point_metrics(actual, fitted.predict(features)["p50"])["mae_mw"]
    naive_mae = point_metrics(actual, SeasonalNaive().predict(features)["p50"])["mae_mw"]

    assert model_mae < 500  # the noise alone gives an MAE of about 320 MW
    assert model_mae < naive_mae / 2


def test_plain_quantile_intervals_are_too_narrow_on_unseen_data(fitted: LightGBMQuantiles) -> None:
    # The reason for ConformalisedLightGBM: fitted quantiles partly fit the training noise.
    features, actual = synthetic(4_000, seed=3)

    forecast = fitted.predict(features)

    assert interval_coverage(actual, forecast["p10"], forecast["p90"]) < 0.77


def test_conformalised_intervals_cover_about_80_percent_of_unseen_actuals() -> None:
    model = ConformalisedLightGBM()
    model.fit(*synthetic(12_000, seed=1))
    features, actual = synthetic(4_000, seed=3)

    forecast = model.predict(features)

    assert model.margin_mw is not None and model.margin_mw > 0  # it had to widen the band
    assert interval_coverage(actual, forecast["p10"], forecast["p90"]) == pytest.approx(0.8, abs=0.03)
    assert (forecast["p10"] <= forecast["p50"]).all() and (forecast["p50"] <= forecast["p90"]).all()


def test_conformal_margin_is_the_finite_sample_quantile_of_the_scores() -> None:
    band = pd.DataFrame({"p10": [100.0] * 9, "p90": [200.0] * 9})
    # Actuals 10 MW inside, on the edge, or 10-70 MW outside the band: scores -10, 0, 10, 20, ..., 70 MW.
    actual = np.array([110, 200, 210, 220, 230, 240, 250, 260, 30], dtype="float64")

    # ceil((9 + 1) x 0.8) = the 8th smallest of the 9 scores, not the largest.
    assert conformal_margin(actual, band, 0.8) == 60
    # Asking for more coverage than 9 scores can support takes the largest.
    assert conformal_margin(actual, band, 0.95) == 70


def test_conformalised_model_needs_room_for_a_calibration_window() -> None:
    with pytest.raises(ValueError, match="calibration window"):
        ConformalisedLightGBM().fit(*synthetic(1_000, seed=8))


def test_quantiles_never_cross(fitted: LightGBMQuantiles) -> None:
    # Far outside the training range is where separately trained quantile models are most likely to cross.
    features, _ = synthetic(2_000, seed=4)
    features["temperature_c"] = 40.0

    forecast = fitted.predict(features)

    assert (forecast["p10"] <= forecast["p50"]).all()
    assert (forecast["p50"] <= forecast["p90"]).all()


def test_missing_inputs_are_allowed(fitted: LightGBMQuantiles) -> None:
    features, _ = synthetic(10, seed=5)
    features["temperature_c"] = np.nan

    assert fitted.predict(features).notna().all().all()


def test_the_same_data_gives_the_same_model() -> None:
    features, target = synthetic(3_000, seed=6)
    a, b = LightGBMQuantiles(), LightGBMQuantiles()
    a.fit(features, target)
    b.fit(features, target)

    pd.testing.assert_frame_equal(a.predict(features), b.predict(features))


def test_feature_importance_names_what_drives_the_forecast(fitted: LightGBMQuantiles) -> None:
    importance = fitted.feature_importance()

    assert importance.sum() == pytest.approx(1.0)
    assert {"half_hour_of_day", "temperature_c"} <= set(importance.index[:3])


def test_predicting_before_fitting_is_an_error() -> None:
    with pytest.raises(RuntimeError, match="fit the model"):
        LightGBMQuantiles().predict(synthetic(5, seed=7)[0])
