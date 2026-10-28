#!/usr/bin/env bash
# Runs once when the dev container is created.
set -euo pipefail

pipx install uv

# Local-only .env with a random database password; never committed (.gitignore).
if [[ ! -f .env ]]; then
  password=$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')
  sed "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=${password}/" .env.example > .env
  echo "Created .env with a random POSTGRES_PASSWORD"
fi

echo
echo "Toolchain:"
java -version 2>&1 | head -1
dotnet --version
python3 --version
uv --version
docker compose version
