"""Forecasters share one small interface so the backtest can treat a baseline and LightGBM alike."""

from __future__ import annotations

from typing import ClassVar, Protocol

import lightgbm as lgb
import numpy as np
import pandas as pd


class Forecaster(Protocol):
    """Fits on feature rows and predicts a median (``p50``), optionally with ``p10`` and ``p90`` columns."""

    name: str

    def fit(self, features: pd.DataFrame, target: pd.Series) -> None: ...

    def predict(self, features: pd.DataFrame) -> pd.DataFrame: ...

    def params(self) -> dict[str, str | int | float]: ...


class SeasonalNaive:
    """Demand at the same UK clock time a week earlier: the floor any model must clear (ADR-0010)."""

    name = "seasonal-naive"

    def fit(self, features: pd.DataFrame, target: pd.Series) -> None:
        """Nothing to learn."""

    def predict(self, features: pd.DataFrame) -> pd.DataFrame:
        return pd.DataFrame({"p50": features["demand_lag_7d"]}, index=features.index)

    def params(self) -> dict[str, str | int | float]:
        return {"lag_days": 7}


class LightGBMQuantiles:
    """One gradient-boosted tree model per quantile: P10, P50 and P90 (ADR-0011).

    The quantile (pinball) objective makes each model estimate that quantile directly, so the P10-P90 band is a
    learned 80% interval rather than a symmetric error bar. P50, the median, is the point forecast.
    """

    name = "lightgbm-quantile"

    # Chosen once, before any backtest, and not tuned on the test months: tuning on them would overstate accuracy.
    PARAMS: ClassVar[dict[str, str | int | float | bool]] = {
        "learning_rate": 0.03,
        "num_leaves": 31,
        "min_data_in_leaf": 100,  # leaves of at least ~2 days of half-hours: smooths over one-off days
        "feature_fraction": 0.9,
        "bagging_fraction": 0.8,
        "bagging_freq": 1,
        "lambda_l2": 1.0,
        "seed": 42,
        "deterministic": True,  # with force_row_wise, the same data gives the same model on any machine
        "force_row_wise": True,
        "verbosity": -1,
    }
    NUM_BOOST_ROUND = 1_000
    QUANTILES: ClassVar[dict[str, float]] = {"p10": 0.1, "p50": 0.5, "p90": 0.9}

    def __init__(self) -> None:
        self._boosters: dict[str, lgb.Booster] = {}

    def fit(self, features: pd.DataFrame, target: pd.Series) -> None:
        data = lgb.Dataset(numeric(features), label=target.to_numpy(dtype="float64"), free_raw_data=False)
        self._boosters = {
            label: lgb.train({**self.PARAMS, "objective": "quantile", "alpha": alpha}, data, self.NUM_BOOST_ROUND)
            for label, alpha in self.QUANTILES.items()
        }

    def predict(self, features: pd.DataFrame) -> pd.DataFrame:
        if not self._boosters:
            raise RuntimeError("fit the model before predicting")
        x = numeric(features)
        raw = np.column_stack([np.asarray(self._boosters[label].predict(x)) for label in self.QUANTILES])
        # Separately trained quantile models can cross (P10 above P50) on odd inputs. Sorting each row is the
        # standard fix ("rearrangement", Chernozhukov et al. 2010): it can only move estimates closer to the truth.
        ordered = np.sort(raw, axis=1)
        return pd.DataFrame(ordered, columns=list(self.QUANTILES), index=features.index)

    def params(self) -> dict[str, str | int | float]:
        return {
            **{k: v for k, v in self.PARAMS.items() if not isinstance(v, bool)},
            "num_boost_round": self.NUM_BOOST_ROUND,
        }

    def feature_importance(self) -> pd.Series:
        """Share of the median model's total split gain per feature: what the forecast leans on."""
        booster = self._boosters["p50"]
        gain = pd.Series(booster.feature_importance(importance_type="gain"), index=booster.feature_name())
        return (gain / gain.sum()).sort_values(ascending=False)


class ConformalisedLightGBM:
    """LightGBM quantiles with the P10-P90 band calibrated on recent held-out data (split CQR, ADR-0011).

    Quantile models fitted to past data give intervals that are too narrow on new data: they partly fit the
    training noise, and the world drifts. Conformalised quantile regression (Romano et al. 2019) measures how far
    actuals fell outside the band on data the interval models never saw, and widens (or narrows) the band by that
    much. Here that data is the most recent ``CALIBRATION_ROWS`` training rows, which assumes rows in time order,
    as the backtest provides them:

    - P10 and P90 come from models fitted on everything before the calibration window, then calibrated on it.
    - P50, the point forecast, needs no calibration, so it is fitted on all rows, the most recent included.
    """

    name = "lightgbm-cqr"

    CALIBRATION_ROWS = 8 * 7 * 48  # the last 8 weeks of half-hours
    COVERAGE = 0.8

    def __init__(self) -> None:
        self._point = LightGBMQuantiles()
        self._band = LightGBMQuantiles()
        self._margin_mw: float | None = None

    def fit(self, features: pd.DataFrame, target: pd.Series) -> None:
        if len(features) <= 2 * self.CALIBRATION_ROWS:
            raise ValueError(f"need more than {2 * self.CALIBRATION_ROWS} rows to hold out a calibration window")
        proper, calibration = slice(None, -self.CALIBRATION_ROWS), slice(-self.CALIBRATION_ROWS, None)
        self._band.fit(features.iloc[proper], target.iloc[proper])
        self._margin_mw = conformal_margin(
            target.iloc[calibration].to_numpy(dtype="float64"),
            self._band.predict(features.iloc[calibration]),
            self.COVERAGE,
        )
        self._point.fit(features, target)

    def predict(self, features: pd.DataFrame) -> pd.DataFrame:
        if self._margin_mw is None:
            raise RuntimeError("fit the model before predicting")
        band = self._band.predict(features)
        raw = np.column_stack(
            [
                band["p10"] - self._margin_mw,
                self._point.predict(features)["p50"],
                band["p90"] + self._margin_mw,
            ]
        )
        return pd.DataFrame(np.sort(raw, axis=1), columns=["p10", "p50", "p90"], index=features.index)

    def params(self) -> dict[str, str | int | float]:
        return {**self._point.params(), "calibration_rows": self.CALIBRATION_ROWS, "target_coverage": self.COVERAGE}

    def feature_importance(self) -> pd.Series:
        return self._point.feature_importance()

    @property
    def margin_mw(self) -> float | None:
        """How much calibration widened each side of the band (negative: narrowed)."""
        return self._margin_mw


def conformal_margin(actual: np.ndarray, band: pd.DataFrame, coverage: float) -> float:
    """The CQR adjustment: the finite-sample ``coverage`` quantile of how far actuals fell outside [p10, p90].

    A score is positive when the actual is outside the band (by that many MW) and negative when inside. Widening
    the band by the margin makes it hold ``coverage`` of the calibration actuals, with the (n + 1) correction that
    makes the guarantee exact for exchangeable data.
    """
    scores = np.sort(np.maximum(band["p10"].to_numpy() - actual, actual - band["p90"].to_numpy()))
    # The ceil((n + 1) x coverage)-th smallest score; with too few scores for that, the largest.
    rank = min(len(scores), int(np.ceil((len(scores) + 1) * coverage)))
    return float(scores[rank - 1])


def numeric(features: pd.DataFrame) -> pd.DataFrame:
    """LightGBM wants numbers: booleans become 0/1, NaN stays NaN (LightGBM learns where missing values go)."""
    return features.astype("float64")
