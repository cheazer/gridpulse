-- GridPulse's own forecasts (ADR-0011): written by the forecast job, read by the forecasting API and,
-- in Phase 3, the scheduler and dashboard. One row per (task, model, issue time, half-hour).

CREATE TABLE model_forecast (
    task          text             NOT NULL CHECK (task IN ('demand', 'carbon')),
    model         text             NOT NULL,  -- e.g. lightgbm-cqr
    model_version text             NOT NULL,  -- what produced it: a git commit now, a registry version from 2.7
    issued_at     timestamptz      NOT NULL,  -- the information cut-off: nothing later was used
    valid_from    timestamptz      NOT NULL,
    valid_to      timestamptz      NOT NULL,
    unit          text             NOT NULL,
    p10           double precision NOT NULL,
    p50           double precision NOT NULL,
    p90           double precision NOT NULL,
    created_at    timestamptz      NOT NULL DEFAULT now(),
    -- Re-running a forecast for the same issue time replaces it rather than duplicating it.
    PRIMARY KEY (task, model, issued_at, valid_from),
    CHECK (valid_to > valid_from),
    CHECK (valid_from >= issued_at),  -- a forecast, not a nowcast of the past
    CHECK (p10 <= p50 AND p50 <= p90)
);
SELECT create_hypertable('model_forecast', by_range('valid_from', INTERVAL '30 days'));
CREATE INDEX ON model_forecast (task, valid_from, issued_at DESC);

-- The freshest forecast for each half-hour and task: what the API serves by default.
CREATE VIEW model_forecast_latest AS
SELECT DISTINCT ON (task, valid_from)
    task, model, model_version, issued_at, valid_from, valid_to, unit, p10, p50, p90
FROM model_forecast
ORDER BY task, valid_from, issued_at DESC;

-- Least privilege. The forecast job's login is granted this role (deploy/compose/scripts/create-db-users.sh);
-- it may write model forecasts and nothing else.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'forecast_writer') THEN
        CREATE ROLE forecast_writer NOLOGIN;
    END IF;
END
$$;
GRANT USAGE ON SCHEMA grid TO forecast_writer;
GRANT SELECT, INSERT, UPDATE ON model_forecast TO forecast_writer;
-- V1's default privileges gave the ingestion writer full rights on every new table. It has no business here.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON model_forecast FROM grid_writer;
