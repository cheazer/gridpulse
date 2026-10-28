#!/usr/bin/env bash
# Migrate a TimescaleDB container and run the schema assertions against it.
# Used locally, in Codespaces and in CI. Run from the repo root:  bash db/tests/run.sh
set -euo pipefail

set -a; source .env; set +a

docker compose up -d --wait timescaledb

echo "--- migrate"
docker compose run --rm migrate
echo "--- migrate again (must be a no-op)"
docker compose run --rm migrate | tee /tmp/gridpulse-migrate-2.log
grep -q "Schema \"grid\" is up to date" /tmp/gridpulse-migrate-2.log

echo "--- schema assertions"
docker compose exec -T timescaledb \
  psql -X -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" < db/tests/schema_test.sql
echo "db tests passed"
