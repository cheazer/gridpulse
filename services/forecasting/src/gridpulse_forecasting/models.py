"""Forecasters share one small interface so the backtest can treat a baseline and LightGBM alike."""

from __future__ import annotations

from typing import Protocol

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
