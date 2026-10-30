"""Forecast accuracy metrics (ADR-0010, ADR-0011). Inputs are aligned arrays with no missing values."""

from __future__ import annotations

import numpy as np
import numpy.typing as npt

Values = npt.ArrayLike


def point_metrics(actual: Values, forecast: Values) -> dict[str, float]:
    """MAE, RMSE and bias in MW, MAPE in percent. Bias is mean(forecast - actual): positive means too high."""
    a, f = _floats(actual), _floats(forecast)
    error = f - a
    return {
        "mae_mw": float(np.mean(np.abs(error))),
        "rmse_mw": float(np.sqrt(np.mean(error**2))),
        "mape_pct": float(np.mean(np.abs(error) / np.abs(a)) * 100),
        "bias_mw": float(np.mean(error)),
    }


def pinball_loss(actual: Values, forecast: Values, quantile: float) -> float:
    """Mean quantile loss: under-forecasts cost ``quantile`` per MW, over-forecasts ``1 - quantile``.

    It is the loss a quantile forecast minimises, so it scores a P10 or P90 the way MAE scores a median.
    """
    a, f = _floats(actual), _floats(forecast)
    error = a - f
    return float(np.mean(np.maximum(quantile * error, (quantile - 1) * error)))


def interval_coverage(actual: Values, lower: Values, upper: Values) -> float:
    """Share of actuals inside [lower, upper]: an honest 80% interval covers about 0.8."""
    a = _floats(actual)
    return float(np.mean((a >= _floats(lower)) & (a <= _floats(upper))))


def _floats(values: Values) -> npt.NDArray[np.float64]:
    array = np.asarray(values, dtype=np.float64)
    if np.isnan(array).any():
        raise ValueError("metrics need complete data; align forecasts and drop missing rows first")
    return array
