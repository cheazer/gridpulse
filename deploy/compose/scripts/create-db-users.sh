#!/usr/bin/env bash
# Create per-service login users and grant them a group role from V1 (ADR-0005, ADR-0011).
# Passwords come from the environment, never from migrations. Safe to re-run: an existing user
# gets its password reset to the current value, so rotating it is "change .env, run this again".
set -euo pipefail

: "${FORECAST_DB_PASSWORD:?set FORECAST_DB_PASSWORD in .env}"

# psql variables (-v) are quoted by psql itself (:'name'), so a password can't break out of the SQL.
psql -X -q -v ON_ERROR_STOP=1 -v forecast_password="$FORECAST_DB_PASSWORD" <<'SQL'
SET client_min_messages = warning;  -- re-runs: "already a member" isn't news
SELECT 'CREATE ROLE forecasting LOGIN'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'forecasting') \gexec
ALTER ROLE forecasting PASSWORD :'forecast_password';
GRANT grid_reader TO forecasting;
SQL

echo "db users ready: forecasting (grid_reader)"
