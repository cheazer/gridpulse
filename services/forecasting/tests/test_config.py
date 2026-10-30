import pytest
from pydantic import ValidationError

from gridpulse_forecasting.config import DatabaseSettings

VARIABLES = ["POSTGRES_HOST", "TIMESCALE_PORT", "POSTGRES_DB", "FORECAST_DB_USER", "FORECAST_DB_PASSWORD"]


@pytest.fixture(autouse=True)
def clean_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in VARIABLES:
        monkeypatch.delenv(name, raising=False)


def test_reads_the_shared_env_variable_names(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POSTGRES_HOST", "timescaledb")
    monkeypatch.setenv("TIMESCALE_PORT", "15432")
    monkeypatch.setenv("POSTGRES_DB", "grid")
    monkeypatch.setenv("FORECAST_DB_USER", "forecaster")
    monkeypatch.setenv("FORECAST_DB_PASSWORD", "s3cret")

    settings = DatabaseSettings()

    assert (settings.host, settings.port, settings.dbname, settings.user) == (
        "timescaledb",
        15432,
        "grid",
        "forecaster",
    )
    assert settings.password.get_secret_value() == "s3cret"


def test_defaults_everything_but_the_password(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FORECAST_DB_PASSWORD", "s3cret")

    settings = DatabaseSettings()

    assert (settings.host, settings.port, settings.dbname, settings.user) == (
        "localhost",
        5432,
        "gridpulse",
        "forecasting",
    )


def test_fails_fast_without_a_password() -> None:
    with pytest.raises(ValidationError, match="FORECAST_DB_PASSWORD"):
        DatabaseSettings()


def test_password_never_appears_in_repr(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FORECAST_DB_PASSWORD", "s3cret")

    assert "s3cret" not in repr(DatabaseSettings())
