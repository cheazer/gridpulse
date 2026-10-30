"""Configuration from environment variables only; nothing secret has a default.

Locally, pass the repo's .env:  uv run --env-file ../../.env ...
"""

from __future__ import annotations

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class DatabaseSettings(BaseSettings):
    """How to reach TimescaleDB as the forecasting service's own read-only login (ADR-0011).

    The variable names match the rest of the stack (.env.example), so one .env serves every service.
    """

    model_config = SettingsConfigDict(frozen=True, extra="ignore")

    host: str = Field(default="localhost", validation_alias="POSTGRES_HOST")
    port: int = Field(default=5432, validation_alias="TIMESCALE_PORT")
    dbname: str = Field(default="gridpulse", validation_alias="POSTGRES_DB")
    user: str = Field(default="forecasting", validation_alias="FORECAST_DB_USER")
    # SecretStr keeps the password out of repr(), logs and tracebacks.
    password: SecretStr = Field(validation_alias="FORECAST_DB_PASSWORD")


class JobDatabaseSettings(DatabaseSettings):
    """The forecast job's login: reads like the service, and may also write model forecasts (V7)."""

    user: str = Field(default="forecast_job", validation_alias="FORECAST_JOB_DB_USER")
    password: SecretStr = Field(validation_alias="FORECAST_JOB_DB_PASSWORD")
