"""What can be forecast: national demand (step 2.4) and national carbon intensity (step 2.5).

A task bundles everything that differs between them, so the dataset builder, backtest, report and CLI are shared.
"""

from __future__ import annotations

import datetime as dt
from collections.abc import Callable
from dataclasses import dataclass, field
from functools import partial

import pandas as pd

from gridpulse_forecasting.dataset import ROLLING_ISSUE_HOURS
from gridpulse_forecasting.db import Database
from gridpulse_forecasting.experiments import variant_models
from gridpulse_forecasting.features import CARBON_FEATURES
from gridpulse_forecasting.models import (
    ConformalisedLightGBM,
    Forecaster,
    LightGBMQuantiles,
    ModelConfig,
    SeasonalNaive,
)
from gridpulse_forecasting.sources import load_carbon_actuals, load_neso_carbon_final, load_neso_day_ahead, load_targets

Loader = Callable[[Database, dt.datetime, dt.datetime], pd.Series]


@dataclass(frozen=True)
class Task:
    name: str
    title: str
    unit: str
    load_targets: Loader
    load_benchmark: Loader
    benchmark_label: str
    # One sentence for reports: what the target is and what the benchmark is, honestly.
    about: str
    naive_feature: str
    experiment: str
    # The model the forecast job runs: trained on rolling examples (every lead time up to 48 hours).
    production: Callable[[], Forecaster]
    models: dict[str, Callable[[], Forecaster]] = field(default_factory=dict)


CARBON_CONFIG = ModelConfig(features=tuple(CARBON_FEATURES))

# CQR's calibration window, 8 weeks, in rolling-example rows: 4 issue times a day x 96 half-hours each.
ROLLING_CALIBRATION_ROWS = 8 * 7 * len(ROLLING_ISSUE_HOURS) * 96

DEMAND = Task(
    name="demand",
    title="Day-ahead demand forecast accuracy",
    unit="MW",
    load_targets=load_targets,
    load_benchmark=load_neso_day_ahead,
    benchmark_label="NESO day-ahead",
    about=(
        "Actuals are NESO's settled national demand (ND), or Elexon's initial outturn where ND isn't published yet. "
        "NESO's forecast is its day-ahead NDF as published by 13:00 UK time the day before: the same information "
        "cut-off as ours (ADR-0010)."
    ),
    naive_feature="demand_lag_7d",
    experiment="day-ahead-demand",
    production=partial(ConformalisedLightGBM, ModelConfig(), ROLLING_CALIBRATION_ROWS),
    models={
        "seasonal-naive": partial(SeasonalNaive, "demand_lag_7d"),
        "lightgbm-quantile": LightGBMQuantiles,
        "lightgbm-cqr": ConformalisedLightGBM,
        **variant_models(),
    },
)

CARBON = Task(
    name="carbon",
    title="Day-ahead carbon intensity forecast accuracy",
    unit="gCO2/kWh",
    load_targets=load_carbon_actuals,
    load_benchmark=load_neso_carbon_final,
    benchmark_label="NESO final (not day-ahead)",
    about=(
        "Actuals are NESO's national (GB) carbon intensity. **The NESO benchmark is not a day-ahead forecast**: it "
        "is NESO's final forecast for each half-hour, made shortly before it, the only NESO forecast recorded for "
        "this period (day-ahead revisions are recorded from late October 2026). It knew far more than a day-ahead "
        "model can, so it is a ceiling, not a like-for-like comparison. MAE is the headline metric: carbon "
        "intensity can fall close to zero, where percentage errors explode."
    ),
    naive_feature="carbon_lag_7d",
    experiment="day-ahead-carbon",
    production=partial(ConformalisedLightGBM, CARBON_CONFIG, ROLLING_CALIBRATION_ROWS),
    models={
        "seasonal-naive": partial(SeasonalNaive, "carbon_lag_7d"),
        "lightgbm-quantile": partial(LightGBMQuantiles, CARBON_CONFIG),
        "lightgbm-cqr": partial(ConformalisedLightGBM, CARBON_CONFIG),
    },
)

TASKS = {task.name: task for task in (DEMAND, CARBON)}
