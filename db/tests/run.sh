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
echo "--- login users (twice: must be idempotent)"
docker compose run --rm db-users
docker compose run --rm db-users
docker compose exec -T -e PGPASSWORD="$FORECAST_DB_PASSWORD" timescaledb \
  psql -X -q -h localhost -U forecasting -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 <<'SQL'
SELECT count(*) FROM grid.national_demand_actual;
DO $$
BEGIN
    DELETE FROM grid.demand;
    RAISE EXCEPTION 'forecasting login was allowed to delete';
EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  forecasting logs in with its own password, reads, and cannot write';
END $$;
SQL
docker compose exec -T -e PGPASSWORD="$MLFLOW_DB_PASSWORD" timescaledb \
  psql -X -q -h localhost -U mlflow -d mlflow -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE ownership_check (id int);  -- mlflow owns its database, so it can create its schema
DROP TABLE ownership_check;
SELECT 'PASS  mlflow logs in to its own database and owns it' AS result;
SQL
if docker compose exec -T -e PGPASSWORD="$MLFLOW_DB_PASSWORD" timescaledb \
     psql -X -q -h localhost -U mlflow -d "$POSTGRES_DB" -c "SELECT 1 FROM grid.demand LIMIT 1" 2>/dev/null; then
  echo "FAIL  mlflow can read grid data"; exit 1
fi
echo "PASS  mlflow cannot read grid data"
echo "db tests passed"
