-- Least privilege for the scheduler (ADR-0013). It reads NESO's carbon intensity forecasts directly, because no
-- service serves them yet, and nothing else: GridPulse's own forecasts it gets from the forecasting API.
-- The 'scheduler' login is created and granted this role by deploy/compose/scripts/create-db-users.sh.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'scheduler_reader') THEN
        CREATE ROLE scheduler_reader NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA grid TO scheduler_reader;
GRANT SELECT ON carbon_intensity TO scheduler_reader;
