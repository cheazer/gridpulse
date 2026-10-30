# One image for the forecasting API and the forecast job (ADR-0011):
#   API:  the default command
#   job:  python -m gridpulse_forecasting forecast --task all
# Build from the repo root:  docker build -f services/forecasting/Dockerfile -t gridpulse-forecasting .

FROM ghcr.io/astral-sh/uv:0.11.21 AS uv

# ---------------------------------------------------------------- build: dependencies, then the project
FROM python:3.12.16-slim AS build
COPY --from=uv /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /src

# Dependencies first, from the lockfile only, so code changes don't invalidate this (slow) layer.
COPY services/forecasting/pyproject.toml services/forecasting/uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv,sharing=locked \
    uv sync --locked --no-dev --no-install-project

COPY services/forecasting/README.md ./
COPY services/forecasting/src ./src
RUN --mount=type=cache,target=/root/.cache/uv,sharing=locked \
    uv sync --locked --no-dev --no-editable

# ---------------------------------------------------------------- runtime
FROM python:3.12.16-slim
# LightGBM needs the OpenMP runtime.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgomp1 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /app gridpulse

COPY --from=build /app/.venv /app/.venv
ENV PATH=/app/.venv/bin:$PATH \
    PYTHONUNBUFFERED=1

# The commit the image was built from, recorded with every stored forecast (model_version) and MLflow run.
ARG GRIDPULSE_VERSION=unknown
ENV GRIDPULSE_VERSION=$GRIDPULSE_VERSION

USER gridpulse
WORKDIR /app
EXPOSE 8000
CMD ["uvicorn", "gridpulse_forecasting.api:app", "--host", "0.0.0.0", "--port", "8000"]
