#!/usr/bin/env bash
# Runs once when the dev container is created.
set -euo pipefail

pipx install uv

# Local-only .env with random database passwords; never committed (.gitignore).
if [[ ! -f .env ]]; then
  random() { python3 -c 'import secrets; print(secrets.token_urlsafe(18))'; }
  sed -e "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$(random)/" \
      -e "s/^FORECAST_DB_PASSWORD=.*/FORECAST_DB_PASSWORD=$(random)/" \
      -e "s/^MLFLOW_DB_PASSWORD=.*/MLFLOW_DB_PASSWORD=$(random)/" .env.example > .env
  echo "Created .env with random database passwords"
fi

echo
echo "Toolchain:"
java -version 2>&1 | head -1
dotnet --version
python3 --version
uv --version
docker compose version
