"""Model variants compared in the step 2.4 improvement round, on the validation window only (ADR-0011).

Each variant changes one thing from the first model, so a difference in validation error can be attributed to it.
The winner becomes the production configuration; the test months are scored with it once.
"""

from __future__ import annotations

from collections.abc import Callable
from functools import partial

from gridpulse_forecasting.features import BASE_FEATURES, REGIONAL_SOLAR, WEEK_ON_WEEK
from gridpulse_forecasting.models import BASE_PARAMS, Forecaster, LightGBMQuantiles, ModelConfig

# Months used to choose between variants: the three before the test window (Oct 2025 to Sep 2026).
VALIDATION_LAST_DAY = "2025-09-30"
VALIDATION_MONTHS = 3

WITH_REGIONAL_SOLAR = (*BASE_FEATURES, *REGIONAL_SOLAR)

VARIANTS: dict[str, ModelConfig] = {
    "v1": ModelConfig(),
    "regional-solar": ModelConfig(features=WITH_REGIONAL_SOLAR),
    "ratio": ModelConfig(target="ratio_to_last_week"),
    "regional-solar-ratio": ModelConfig(features=WITH_REGIONAL_SOLAR, target="ratio_to_last_week"),
    # Capacity and learning rate, each on its own.
    "leaves-63": ModelConfig(params={**BASE_PARAMS, "num_leaves": 63}),
    "leaves-15": ModelConfig(params={**BASE_PARAMS, "num_leaves": 15}),
    "lr-0.06-500": ModelConfig(params={**BASE_PARAMS, "learning_rate": 0.06}, num_boost_round=500),
    "min-leaf-30": ModelConfig(params={**BASE_PARAMS, "min_data_in_leaf": 30}),
    # Second round, after the first found nothing: tell the model how this week differs from last week.
    "week-on-week": ModelConfig(features=(*BASE_FEATURES, *WEEK_ON_WEEK)),
    "week-on-week-regional-solar": ModelConfig(features=(*WITH_REGIONAL_SOLAR, *WEEK_ON_WEEK)),
}


def variant_models() -> dict[str, Callable[[], Forecaster]]:
    """Each variant as a quantile model; P50 is what the round compares, and it is the same with or without CQR."""
    return {f"exp-{name}": partial(LightGBMQuantiles, config) for name, config in VARIANTS.items()}
