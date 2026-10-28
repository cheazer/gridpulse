-- Extension, access roles and default privileges for the grid schema (ADR-0005).
-- The schema itself is created by Flyway (FLYWAY_SCHEMAS=grid) so `flyway clean` can manage it.

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- NOLOGIN group roles. Per-environment login users are granted one of these;
-- no passwords live in migrations. Roles are cluster-wide, hence the existence checks.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grid_writer') THEN
        CREATE ROLE grid_writer NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grid_reader') THEN
        CREATE ROLE grid_reader NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA grid TO grid_writer, grid_reader;

-- Applies to every table and view the migration user creates in grid from here on.
ALTER DEFAULT PRIVILEGES IN SCHEMA grid GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO grid_writer;
ALTER DEFAULT PRIVILEGES IN SCHEMA grid GRANT SELECT ON TABLES TO grid_reader;
