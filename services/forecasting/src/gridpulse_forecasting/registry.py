"""The MLflow model registry: versions of each task's production model, and which one is the champion (ADR-0011).

Each registered version records its training cutoff (``training_cutoff``: no data after it was used) so a later
challenger can be compared with it fairly (training.py). The forecast job loads ``models:/<name>@champion``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import mlflow
import mlflow.pyfunc
import pandas as pd
from mlflow.exceptions import MlflowException
from mlflow.pyfunc.model import PythonModel

from gridpulse_forecasting.models import Forecaster

ALIAS = "champion"


def model_name(task: str) -> str:
    return f"gridpulse-{task}"


@dataclass(frozen=True)
class Registered:
    name: str
    version: str
    training_cutoff: pd.Timestamp
    model: Forecaster

    @property
    def label(self) -> str:
        """What the forecast job records as model_version: registry version and the code that trained it."""
        return f"{self.name}/v{self.version}"


class ForecasterModel(PythonModel):
    """Wraps a fitted Forecaster so MLflow can store and load it. predict() takes feature rows, returns P10/P50/P90.

    The Forecaster is pickled with the model, so loading it needs this package installed: true in the image that
    runs the job, and the reason the registry and the code version are recorded together.
    """

    def __init__(self, forecaster: Forecaster) -> None:
        self.forecaster = forecaster

    def predict(self, context: Any, model_input: pd.DataFrame, params: Any = None) -> pd.DataFrame:
        return self.forecaster.predict(model_input)


def register(
    task: str,
    model: Forecaster,
    training_cutoff: pd.Timestamp,
    tags: dict[str, str],
    promote: bool,
) -> str:
    """Log the fitted model in the active MLflow run, register it as a new version and, if asked, make it champion."""
    name = model_name(task)
    logged = mlflow.pyfunc.log_model(name="model", python_model=ForecasterModel(model))
    version = mlflow.register_model(logged.model_uri, name)
    client = mlflow.MlflowClient()
    for key, value in {**tags, "training_cutoff": training_cutoff.isoformat()}.items():
        client.set_model_version_tag(name, version.version, key, value)
    if promote:
        client.set_registered_model_alias(name, ALIAS, version.version)
    return str(version.version)


def load_champion(task: str) -> Registered | None:
    """The current champion with its fitted model, or None if the task has none yet."""
    name = model_name(task)
    client = mlflow.MlflowClient()
    try:
        version = client.get_model_version_by_alias(name, ALIAS)
    except MlflowException:
        return None
    loaded = mlflow.pyfunc.load_model(f"models:/{name}@{ALIAS}")
    forecaster: Forecaster = loaded.unwrap_python_model().forecaster
    return Registered(
        name=name,
        version=str(version.version),
        training_cutoff=pd.Timestamp(version.tags["training_cutoff"]),
        model=forecaster,
    )
