from __future__ import annotations

import dataclasses
from pathlib import Path
from typing import ClassVar

import numpy as np
import pandas as pd
import pytest
from test_backtest import examples

from gridpulse_forecasting import training
from gridpulse_forecasting.tasks import DEMAND
from gridpulse_forecasting.training import Scores, challenge, decide, evaluation_rows, fit_rows

# ------------------------------------------------------------------ the gate


def scores(mae: float = 1_000, naive_mae: float = 2_000, coverage: float = 0.8) -> Scores:
    return Scores(mae=mae, naive_mae=naive_mae, coverage=coverage, forecasts=1_000)


def test_promotes_when_every_gate_passes() -> None:
    decision = decide(scores(mae=1_000), champion_mae=1_000)

    assert decision.promote
    assert all(reason.startswith("pass") for reason in decision.reasons)


def test_identical_code_ties_and_still_promotes_on_fresher_data() -> None:
    # Within the 1% tolerance: unchanged code scores the same, and a retrain must not be blocked by it.
    assert decide(scores(mae=1_009), champion_mae=1_000).promote


@pytest.mark.parametrize(
    ("challenger", "champion_mae", "failure"),
    [
        (scores(mae=1_020), 1_000.0, "worse than the champion"),
        (scores(mae=1_600, naive_mae=2_000), None, "of seasonal naive"),
        (scores(coverage=0.55), None, "coverage 55%"),
        (scores(coverage=0.99), None, "coverage 99%"),
    ],
)
def test_any_failing_gate_blocks_promotion(challenger: Scores, champion_mae: float | None, failure: str) -> None:
    decision = decide(challenger, champion_mae)

    assert not decision.promote
    assert any(reason.startswith("FAIL") and failure in reason for reason in decision.reasons)


def test_without_a_champion_only_the_absolute_gates_apply() -> None:
    decision = decide(scores(), champion_mae=None)

    assert decision.promote
    assert any("no fair comparison" in reason for reason in decision.reasons)


# ------------------------------------------------------------------ who sees what


class Spy:
    """Forecasts from a good synthetic input (see the frame fixture) and records how many rows it was fitted on."""

    name = "spy"
    fitted_rows: ClassVar[list[int]] = []

    def fit(self, features: pd.DataFrame, target: pd.Series) -> None:
        Spy.fitted_rows.append(len(features))

    def predict(self, features: pd.DataFrame) -> pd.DataFrame:
        p50 = features["temperature_c"].to_numpy()
        # Noise has sd 100 MW, so +/-128 MW is an 80% band.
        return pd.DataFrame({"p10": p50 - 128, "p50": p50, "p90": p50 + 128}, index=features.index)

    def params(self) -> dict[str, str | int | float]:
        return {}


@pytest.fixture
def task() -> object:
    Spy.fitted_rows = []
    return dataclasses.replace(DEMAND, production=Spy)


@pytest.fixture
def frame() -> pd.DataFrame:
    # The spy's input is the target plus a little noise; seasonal naive gets a lot more, so the spy beats it.
    data = examples()
    rng = np.random.default_rng(1)
    data["temperature_c"] = data["target"] + rng.normal(0, 100, len(data))
    data["demand_lag_7d"] = data["target"] + rng.normal(0, 600, len(data))
    return data


def test_challenger_trains_on_exactly_the_champions_data_and_both_are_scored_on_the_rest(
    task: object, frame: pd.DataFrame
) -> None:
    now = pd.Timestamp("2025-04-10T12:00Z")
    champion_cutoff = pd.Timestamp("2025-03-20T12:00Z")  # 3 weeks earlier: a fair comparison

    outcome = challenge(task, frame, now, (Spy(), champion_cutoff))  # type: ignore[arg-type]

    assert outcome.evaluated_since == champion_cutoff
    # First fit: the challenger, on the champion's data. Second: the model to register, on everything.
    assert Spy.fitted_rows == [len(fit_rows(frame, champion_cutoff)), len(fit_rows(frame, now))]
    unseen = evaluation_rows(frame, champion_cutoff, now)
    assert outcome.challenger.forecasts == len(unseen)
    assert (unseen["issue_time"] >= champion_cutoff).all()
    # Same model on the same rows: the champion and challenger tie, and the tie promotes.
    assert outcome.champion_mae == pytest.approx(outcome.challenger.mae)
    assert outcome.decision.promote


def test_a_champion_trained_days_ago_is_not_compared_on_too_little_data(task: object, frame: pd.DataFrame) -> None:
    now = pd.Timestamp("2025-04-10T12:00Z")

    outcome = challenge(task, frame, now, (Spy(), now - pd.Timedelta(days=3)))  # type: ignore[arg-type]

    assert outcome.champion_mae is None
    assert outcome.evaluated_since == now - training.HOLDOUT


def test_training_data_never_includes_actuals_unpublished_at_the_cutoff(frame: pd.DataFrame) -> None:
    cutoff = pd.Timestamp("2025-03-20T12:00Z")

    rows = fit_rows(frame, cutoff)

    assert (rows["target_time"] + pd.Timedelta(minutes=90) <= cutoff).all()
    assert (rows["issue_time"] < pd.Timestamp("2025-03-20T00:00Z")).all()  # issued on earlier UK days


# ------------------------------------------------------------------ the registry


def test_registry_round_trip_and_champion_alias(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    import mlflow

    from gridpulse_forecasting import registry
    from gridpulse_forecasting.models import SeasonalNaive

    monkeypatch.setenv("MLFLOW_TRACKING_URI", tmp_path.as_uri())
    monkeypatch.setenv("MLFLOW_ALLOW_FILE_STORE", "true")
    monkeypatch.setenv("MLFLOW_REGISTRY_URI", tmp_path.as_uri())
    cutoff = pd.Timestamp("2025-04-10T12:00Z")

    assert registry.load_champion("demand") is None  # nothing registered
    mlflow.set_experiment("registry-test")
    with mlflow.start_run():
        first = registry.register("demand", SeasonalNaive(), cutoff, {"git_commit": "abc"}, promote=False)
    assert registry.load_champion("demand") is None  # registered, but it failed its gate: still no champion
    with mlflow.start_run():
        second = registry.register("demand", SeasonalNaive(), cutoff, {"git_commit": "def"}, promote=True)
    with mlflow.start_run():
        third = registry.register("demand", SeasonalNaive(), cutoff, {"git_commit": "ghi"}, promote=False)

    champion = registry.load_champion("demand")
    assert champion is not None
    assert (first, second, third) == ("1", "2", "3")
    assert champion.version == "2"  # v3 was registered but not promoted: the champion doesn't change
    assert champion.training_cutoff == cutoff
    assert champion.label == "gridpulse-demand/v2"
    features = pd.DataFrame({"demand_lag_7d": [25_000.0]})
    assert champion.model.predict(features)["p50"].tolist() == [25_000.0]


def test_a_registry_error_is_not_mistaken_for_no_champion(monkeypatch: pytest.MonkeyPatch) -> None:
    import mlflow
    from mlflow.exceptions import MlflowException

    from gridpulse_forecasting import registry

    class Refusing:
        def get_registered_model(self, name: str) -> None:
            raise MlflowException("Invalid Host header - possible DNS rebinding attack detected")

    monkeypatch.setattr(mlflow, "MlflowClient", Refusing)

    with pytest.raises(MlflowException, match="Invalid Host header"):
        registry.load_champion("demand")


def test_a_manual_promotion_records_why_and_what_it_replaced(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    import mlflow

    from gridpulse_forecasting import registry
    from gridpulse_forecasting.models import SeasonalNaive

    monkeypatch.setenv("MLFLOW_TRACKING_URI", tmp_path.as_uri())
    monkeypatch.setenv("MLFLOW_ALLOW_FILE_STORE", "true")
    monkeypatch.setenv("MLFLOW_REGISTRY_URI", tmp_path.as_uri())
    mlflow.set_experiment("promote-test")
    cutoff = pd.Timestamp("2025-04-10T12:00Z")
    with mlflow.start_run():
        registry.register("demand", SeasonalNaive(), cutoff, {}, promote=False)  # failed its gate

    with pytest.raises(ValueError, match="needs a reason"):
        registry.promote_manually("demand", "1", "  ", pd.Timestamp("2025-04-11T09:00Z"))
    replaced = registry.promote_manually("demand", "1", "coverage 64% on 2 weeks", pd.Timestamp("2025-04-11T09:00Z"))

    champion = registry.load_champion("demand")
    assert replaced is None
    assert champion is not None and champion.version == "1"
    tags = mlflow.MlflowClient().get_model_version("gridpulse-demand", "1").tags
    assert tags["override_reason"] == "coverage 64% on 2 weeks"
    assert tags["decision"] == "promoted manually (gate overridden)"
    assert tags["replaced_champion"] == "none"
