-- Assertions about the migrated schema. Runs in one transaction and rolls back, so it leaves no data.
-- Run with:  bash db/tests/run.sh
\set ON_ERROR_STOP on
SET client_min_messages = notice;
BEGIN;
SET LOCAL search_path = grid, public;

-- ------------------------------------------------------------ structure
DO $$
DECLARE n int;
BEGIN
    SELECT count(*) INTO n FROM timescaledb_information.hypertables WHERE hypertable_schema = 'grid';
    IF n <> 6 THEN RAISE EXCEPTION 'expected 6 hypertables in grid, found %', n; END IF;

    SELECT count(*) INTO n FROM timescaledb_information.dimensions
    WHERE hypertable_schema = 'grid' AND column_name = 'valid_from' AND time_interval = INTERVAL '30 days';
    IF n <> 6 THEN RAISE EXCEPTION 'expected 6 hypertables partitioned on valid_from by 30 days, found %', n; END IF;

    SELECT count(*) INTO n FROM timescaledb_information.continuous_aggregates WHERE view_schema = 'grid';
    IF n <> 3 THEN RAISE EXCEPTION 'expected 3 continuous aggregates, found %', n; END IF;

    SELECT count(*) INTO n FROM timescaledb_information.jobs
    WHERE hypertable_schema = 'grid' AND proc_name = 'policy_retention';
    IF n <> 6 THEN RAISE EXCEPTION 'expected 6 retention policies, found %', n; END IF;

    SELECT count(*) INTO n FROM timescaledb_information.jobs
    WHERE hypertable_schema = 'grid' AND proc_name = 'policy_compression';
    IF n <> 6 THEN RAISE EXCEPTION 'expected 6 columnstore policies, found %', n; END IF;

    SELECT count(*) INTO n FROM timescaledb_information.jobs
    WHERE proc_name = 'policy_refresh_continuous_aggregate' AND hypertable_schema = 'grid';
    IF n <> 3 THEN RAISE EXCEPTION 'expected 3 refresh policies, found %', n; END IF;

    RAISE NOTICE 'PASS  6 hypertables (30-day chunks), 3 continuous aggregates, retention/columnstore/refresh policies';
END $$;

-- ------------------------------------------------------------ idempotent upsert (ADR-0004)
-- Values from schemas/events/v1/examples/valid/carbon-intensity-national-actual.json
INSERT INTO carbon_intensity VALUES
    ('02903352-dee2-504f-abc8-385f37cff117', '2026-10-27T18:00:00Z', '2026-10-27T18:30:00Z', '2026-10-27', 37,
     NULL, '2026-10-27T18:47:12Z', 'neso-carbon-intensity', 'intensity', 'GB', '1.0.0', 168, 172, 'moderate')
ON CONFLICT (event_id, valid_from) DO UPDATE SET actual_gco2_per_kwh = EXCLUDED.actual_gco2_per_kwh;
-- Same event re-delivered with a corrected actual.
INSERT INTO carbon_intensity VALUES
    ('02903352-dee2-504f-abc8-385f37cff117', '2026-10-27T18:00:00Z', '2026-10-27T18:30:00Z', '2026-10-27', 37,
     NULL, '2026-10-27T19:17:12Z', 'neso-carbon-intensity', 'intensity', 'GB', '1.0.0', 168, 175, 'moderate')
ON CONFLICT (event_id, valid_from) DO UPDATE SET actual_gco2_per_kwh = EXCLUDED.actual_gco2_per_kwh;

DO $$
DECLARE n int; v int;
BEGIN
    SELECT count(*), max(actual_gco2_per_kwh) INTO n, v FROM carbon_intensity
    WHERE event_id = '02903352-dee2-504f-abc8-385f37cff117';
    IF n <> 1 OR v <> 175 THEN RAISE EXCEPTION 'upsert: expected 1 row with actual 175, got % rows, actual %', n, v; END IF;
    RAISE NOTICE 'PASS  re-delivered event upserts into one row and takes the corrected value';
END $$;

-- ------------------------------------------------------------ constraints
DO $$
BEGIN
    BEGIN
        INSERT INTO demand VALUES (gen_random_uuid(), '2026-10-25T23:30:00Z', '2026-10-26T00:00:00Z', '2026-10-25', 51,
            NULL, now(), 'elexon-insights', 'indo', 'GB', '1.0.0', 'INDO', 24315);
        RAISE EXCEPTION 'settlement period 51 was accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO demand VALUES (gen_random_uuid(), '2026-10-25T23:30:00Z', '2026-10-25T23:00:00Z', '2026-10-25', 50,
            NULL, now(), 'elexon-insights', 'indo', 'GB', '1.0.0', 'INDO', 24315);
        RAISE EXCEPTION 'valid_to before valid_from was accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO demand_forecast VALUES (gen_random_uuid(), '2026-10-29T17:00:00Z', '2026-10-29T17:30:00Z', '2026-10-29', 35,
            NULL, now(), 'elexon-insights', 'ndf', 'GB', '1.0.0', 'ND', 33850, 'NDF');
        RAISE EXCEPTION 'demand forecast without issued_at was accepted';
    EXCEPTION WHEN not_null_violation THEN NULL;
    END;
    RAISE NOTICE 'PASS  constraints reject period 51, reversed intervals and forecasts without issued_at';
END $$;

-- ------------------------------------------------------------ aggregates: London days and energy by fuel
-- SP50 on the clock-change day: 23:30Z on 25 Oct is still 25 Oct in London.
INSERT INTO demand VALUES
    ('547255fd-a437-53b6-8feb-537e51215ab4', '2026-10-25T23:30:00Z', '2026-10-26T00:00:00Z', '2026-10-25', 50,
     NULL, now(), 'elexon-insights', 'indo', 'GB', '1.0.0', 'INDO', 24315),
    -- SP1 on the same day starts at 23:00Z the previous evening (00:00 BST).
    (gen_random_uuid(), '2026-10-24T23:00:00Z', '2026-10-24T23:30:00Z', '2026-10-25', 1,
     NULL, now(), 'elexon-insights', 'indo', 'GB', '1.0.0', 'INDO', 22000);

INSERT INTO generation_mix VALUES
    ('1f911f21-645e-5af7-b82c-ce53550d2bdb', '2026-10-27T18:00:00Z', '2026-10-27T18:30:00Z', '2026-10-27', 37,
     NULL, now(), 'elexon-insights', 'fuelhh', 'GB', '1.0.0', 'MW', 'gas', 'CCGT', 11240),
    ('1f911f21-645e-5af7-b82c-ce53550d2bdb', '2026-10-27T18:00:00Z', '2026-10-27T18:30:00Z', '2026-10-27', 37,
     NULL, now(), 'elexon-insights', 'fuelhh', 'GB', '1.0.0', 'MW', 'gas', 'OCGT', 35);

DO $$
DECLARE n int; d timestamptz; e double precision;
BEGIN
    SELECT count(*), min(day) INTO n, d FROM demand_daily WHERE demand_type = 'INDO';
    IF n <> 1 OR d <> timestamptz '2026-10-25 00:00:00 Europe/London' THEN
        RAISE EXCEPTION 'demand_daily: expected both periods in one London day (2026-10-25), got % bucket(s) starting %', n, d;
    END IF;

    SELECT energy_mwh INTO e FROM generation_mix_daily WHERE fuel = 'gas';
    IF e IS DISTINCT FROM (11240 + 35) * 0.5 THEN
        RAISE EXCEPTION 'generation_mix_daily: expected gas energy % MWh, got %', (11240 + 35) * 0.5, e;
    END IF;

    SELECT count(*) INTO n FROM carbon_intensity_daily WHERE region = 'GB';
    IF n <> 1 THEN RAISE EXCEPTION 'carbon_intensity_daily: expected the new actual to be visible in real time'; END IF;

    RAISE NOTICE 'PASS  daily aggregates use London days, sum gas across source codes, and include unrefreshed rows';
END $$;

-- ------------------------------------------------------------ least privilege
SET LOCAL ROLE grid_reader;
DO $$
DECLARE n int;
BEGIN
    SELECT count(*) INTO n FROM demand;
    SELECT count(*) INTO n FROM demand_daily;
    BEGIN
        DELETE FROM demand;
        RAISE EXCEPTION 'grid_reader was allowed to delete';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    RAISE NOTICE 'PASS  grid_reader can read tables and aggregates but cannot write';
END $$;
RESET ROLE;

SET LOCAL ROLE grid_writer;
DO $$
BEGIN
    UPDATE demand SET demand_mw = demand_mw WHERE demand_type = 'INDO';
    DELETE FROM generation_mix WHERE event_id = '1f911f21-645e-5af7-b82c-ce53550d2bdb';
    BEGIN
        CREATE TABLE should_not_exist (id int);
        RAISE EXCEPTION 'grid_writer was allowed to create a table';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    RAISE NOTICE 'PASS  grid_writer can write rows but cannot change the schema';
END $$;
RESET ROLE;

ROLLBACK;
