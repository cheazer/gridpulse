#!/usr/bin/env bash
# Create per-service login users (ADR-0005, ADR-0011):
#   forecasting  reads the grid schema (grid_reader from V1)
#   forecast_job reads the grid schema and writes model forecasts (forecast_writer from V7), nothing else
#   mlflow       owns its own 'mlflow' database for experiment tracking, and nothing in grid
# Passwords come from the environment, never from migrations. Safe to re-run: an existing user
# gets its password reset to the current value, so rotating it is "change .env, run this again".
set -euo pipefail

: "${FORECAST_DB_PASSWORD:?set FORECAST_DB_PASSWORD in .env}"
: "${FORECAST_JOB_DB_PASSWORD:?set FORECAST_JOB_DB_PASSWORD in .env}"
: "${MLFLOW_DB_PASSWORD:?set MLFLOW_DB_PASSWORD in .env}"

# psql variables (-v) are quoted by psql itself (:'name'), so a password can't break out of the SQL.
# psql runs each statement on its own, so CREATE DATABASE (which can't run in a transaction) works here.
psql -X -q -v ON_ERROR_STOP=1 \
  -v forecast_password="$FORECAST_DB_PASSWORD" \
  -v forecast_job_password="$FORECAST_JOB_DB_PASSWORD" \
  -v mlflow_password="$MLFLOW_DB_PASSWORD" <<'SQL'
SET client_min_messages = warning;  -- re-runs: "already a member" isn't news

SELECT 'CREATE ROLE forecasting LOGIN'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'forecasting') \gexec
ALTER ROLE forecasting PASSWORD :'forecast_password';
GRANT grid_reader TO forecasting;

SELECT 'CREATE ROLE forecast_job LOGIN'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'forecast_job') \gexec
ALTER ROLE forecast_job PASSWORD :'forecast_job_password';
GRANT grid_reader, forecast_writer TO forecast_job;

SELECT 'CREATE ROLE mlflow LOGIN'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'mlflow') \gexec
ALTER ROLE mlflow PASSWORD :'mlflow_password';
SELECT 'CREATE DATABASE mlflow OWNER mlflow'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'mlflow') \gexec
-- Only its owner (and the admin) may connect to the tracking database.
REVOKE CONNECT ON DATABASE mlflow FROM PUBLIC;
SQL

echo "db users ready: forecasting (grid_reader), forecast_job (+ forecast_writer), mlflow (owns database mlflow)"
