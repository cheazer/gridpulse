# Two images from one Dockerfile (ADR-0011), built from the repo root:
#   api   the forecasting API: FastAPI, psycopg, OpenTelemetry, no modelling stack.
#         docker build -f services/forecasting/Dockerfile --target api -t gridpulse-forecasting-api .
#   jobs  everything: the forecast and train jobs (python -m gridpulse_forecasting forecast|train), and backtests.
#         docker build -f services/forecasting/Dockerfile --target jobs -t gridpulse-forecasting-jobs .
# The API serves forecasts the jobs stored, so it never needs LightGBM, pandas or MLflow (the "jobs" dependency group,
# pyproject.toml), which are most of the jobs image's size.

FROM ghcr.io/astral-sh/uv:0.11.21 AS uv

# ---------------------------------------------------------------- build: dependencies, then the project
FROM python:3.12.16-slim AS build
COPY --from=uv /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /src

# Dependencies first, from the lockfile only, so code changes don't invalidate these (slow) layers. The API's alone
# here; build-jobs adds the jobs group on top.
COPY services/forecasting/pyproject.toml services/forecasting/uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv,sharing=locked \
    uv sync --locked --no-default-groups --no-install-project

COPY services/forecasting/README.md ./
COPY services/forecasting/src ./src
RUN --mount=type=cache,target=/root/.cache/uv,sharing=locked \
    uv sync --locked --no-default-groups --no-editable

FROM build AS build-jobs
RUN --mount=type=cache,target=/root/.cache/uv,sharing=locked \
    uv sync --locked --no-default-groups --group jobs --no-editable

# ---------------------------------------------------------------- runtime, shared
FROM python:3.12.16-slim AS runtime
RUN useradd --system --uid 10001 --home-dir /app gridpulse
ENV PATH=/app/.venv/bin:$PATH \
    PYTHONUNBUFFERED=1
# The commit the image was built from, recorded with every stored forecast (model_version) and MLflow run.
ARG GRIDPULSE_VERSION=unknown
ENV GRIDPULSE_VERSION=$GRIDPULSE_VERSION
WORKDIR /app

# ---------------------------------------------------------------- jobs
FROM runtime AS jobs
# LightGBM needs the OpenMP runtime.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgomp1 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build-jobs /app/.venv /app/.venv
# MLflow looks for git to record the commit; there's none in the image, which records the commit in
# GRIDPULSE_VERSION instead, so its repeated warning is noise.
ENV GIT_PYTHON_REFRESH=quiet
USER gridpulse
CMD ["python", "-m", "gridpulse_forecasting", "forecast", "--task", "all"]

# ---------------------------------------------------------------- api (the default target)
FROM runtime AS api
COPY --from=build /app/.venv /app/.venv
USER gridpulse
EXPOSE 8000
CMD ["uvicorn", "gridpulse_forecasting.api:app", "--host", "0.0.0.0", "--port", "8000"]
