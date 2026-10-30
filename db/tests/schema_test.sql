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
    IF n <> 7 THEN RAISE EXCEPTION 'expected 7 hypertables in grid, found %', n; END IF;

    SELECT count(*) INTO n FROM timescaledb_information.dimensions
    WHERE hypertable_schema = 'grid' AND column_name = 'valid_from' AND time_interval = INTERVAL '30 days';
    IF n <> 7 THEN RAISE EXCEPTION 'expected 7 hypertables partitioned on valid_from by 30 days, found %', n; END IF;

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

    RAISE NOTICE 'PASS  7 hypertables (30-day chunks), 3 continuous aggregates, retention/columnstore/refresh policies';
END $$;

-- ------------------------------------------------------------ idempotent upsert (ADR-0004)
-- Values from the carbon-intensity-national-actual golden example, moved to 2020: these assertions
-- may run against a database already holding live data, and must not collide with it.
INSERT INTO carbon_intensity VALUES
    ('02903352-dee2-504f-abc8-385f37cff117', '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z', '2020-10-27', 37,
     NULL, '2020-10-27T18:47:12Z', 'neso-carbon-intensity', 'intensity', 'GB', '1.0.0', 168, 172, 'moderate')
ON CONFLICT (event_id, valid_from) DO UPDATE SET actual_gco2_per_kwh = EXCLUDED.actual_gco2_per_kwh;
-- Same event re-delivered with a corrected actual.
INSERT INTO carbon_intensity VALUES
    ('02903352-dee2-504f-abc8-385f37cff117', '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z', '2020-10-27', 37,
     NULL, '2020-10-27T19:17:12Z', 'neso-carbon-intensity', 'intensity', 'GB', '1.0.0', 168, 175, 'moderate')
ON CONFLICT (event_id, valid_from) DO UPDATE SET actual_gco2_per_kwh = EXCLUDED.actual_gco2_per_kwh;

DO $$
DECLARE n int; v int;
BEGIN
    SELECT count(*), max(actual_gco2_per_kwh) INTO n, v FROM carbon_intensity
    WHERE event_id = '02903352-dee2-504f-abc8-385f37cff117' AND valid_from = '2020-10-27T18:00:00Z';
    IF n <> 1 OR v <> 175 THEN RAISE EXCEPTION 'upsert: expected 1 row with actual 175, got % rows, actual %', n, v; END IF;
    RAISE NOTICE 'PASS  re-delivered event upserts into one row and takes the corrected value';
END $$;

-- ------------------------------------------------------------ constraints
DO $$
BEGIN
    BEGIN
        INSERT INTO demand VALUES (gen_random_uuid(), '2020-10-25T23:30:00Z', '2020-10-26T00:00:00Z', '2020-10-25', 51,
            NULL, now(), 'elexon-insights', 'indo', 'GB', '1.0.0', 'INDO', 24315);
        RAISE EXCEPTION 'settlement period 51 was accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO demand VALUES (gen_random_uuid(), '2020-10-25T23:30:00Z', '2020-10-25T23:00:00Z', '2020-10-25', 50,
            NULL, now(), 'elexon-insights', 'indo', 'GB', '1.0.0', 'INDO', 24315);
        RAISE EXCEPTION 'valid_to before valid_from was accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO demand_forecast VALUES (gen_random_uuid(), '2020-10-29T17:00:00Z', '2020-10-29T17:30:00Z', '2020-10-29', 35,
            NULL, now(), 'elexon-insights', 'ndf', 'GB', '1.0.0', 'ND', 33850, 'NDF');
        RAISE EXCEPTION 'demand forecast without issued_at was accepted';
    EXCEPTION WHEN not_null_violation THEN NULL;
    END;
    RAISE NOTICE 'PASS  constraints reject period 51, reversed intervals and forecasts without issued_at';
END $$;

DO $$
BEGIN
    -- V5: net exports (imports) and pumping (storage) may be negative; nothing else may.
    INSERT INTO generation_mix VALUES
        (gen_random_uuid(), '2020-10-27T19:00:00Z', '2020-10-27T19:30:00Z', '2020-10-27', 39,
         NULL, now(), 'elexon-insights', 'fuelhh', 'GB', '1.1.0', 'MW', 'imports', 'INTFR', -516),
        (gen_random_uuid(), '2020-10-27T19:00:00Z', '2020-10-27T19:30:00Z', '2020-10-27', 39,
         NULL, now(), 'elexon-insights', 'fuelhh', 'GB', '1.1.0', 'MW', 'storage', 'PS', -1196);
    BEGIN
        INSERT INTO generation_mix VALUES
            (gen_random_uuid(), '2020-10-27T19:00:00Z', '2020-10-27T19:30:00Z', '2020-10-27', 39,
             NULL, now(), 'elexon-insights', 'fuelhh', 'GB', '1.1.0', 'MW', 'wind', 'WIND', -50);
        RAISE EXCEPTION 'negative wind generation was accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    RAISE NOTICE 'PASS  imports and storage may be negative (net export, pumping); other fuels may not';
END $$;

-- ------------------------------------------------------------ aggregates: London days and energy by fuel
-- SP50 on a clock-change day (25 Oct 2020 was one): 23:30Z is still 25 Oct in London.
INSERT INTO demand VALUES
    ('547255fd-a437-53b6-8feb-537e51215ab4', '2020-10-25T23:30:00Z', '2020-10-26T00:00:00Z', '2020-10-25', 50,
     NULL, now(), 'elexon-insights', 'indo', 'GB', '1.0.0', 'INDO', 24315),
    -- SP1 on the same day starts at 23:00Z the previous evening (00:00 BST).
    (gen_random_uuid(), '2020-10-24T23:00:00Z', '2020-10-24T23:30:00Z', '2020-10-25', 1,
     NULL, now(), 'elexon-insights', 'indo', 'GB', '1.0.0', 'INDO', 22000);

INSERT INTO generation_mix VALUES
    ('1f911f21-645e-5af7-b82c-ce53550d2bdb', '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z', '2020-10-27', 37,
     NULL, now(), 'elexon-insights', 'fuelhh', 'GB', '1.0.0', 'MW', 'gas', 'CCGT', 11240),
    ('1f911f21-645e-5af7-b82c-ce53550d2bdb', '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z', '2020-10-27', 37,
     NULL, now(), 'elexon-insights', 'fuelhh', 'GB', '1.0.0', 'MW', 'gas', 'OCGT', 35);

DO $$
DECLARE n int; d timestamptz; e double precision; materialised boolean;
BEGIN
    -- Real-time aggregation only reads raw rows above the materialisation watermark (ADR-0009). On a
    -- database whose aggregates are already materialised past 2020 (e.g. after a backfill), these
    -- uncommitted 2020 rows can't appear, so the check only runs where it can: on a fresh database, as in CI.
    SELECT bool_or(_timescaledb_functions.to_timestamp(_timescaledb_functions.cagg_watermark(mat_hypertable_id))
                   > '2021-01-01')
      INTO materialised
      FROM _timescaledb_catalog.continuous_agg WHERE user_view_schema = 'grid';
    IF materialised THEN
        RAISE NOTICE 'SKIP  daily-aggregate checks: aggregates already materialised past 2020 (checked in CI)';
        RETURN;
    END IF;

    SELECT count(*), min(day) INTO n, d FROM demand_daily WHERE demand_type = 'INDO' AND day < '2021-01-01';
    IF n <> 1 OR d <> timestamptz '2020-10-25 00:00:00 Europe/London' THEN
        RAISE EXCEPTION 'demand_daily: expected both periods in one London day (2020-10-25), got % bucket(s) starting %', n, d;
    END IF;

    SELECT energy_mwh INTO e FROM generation_mix_daily WHERE fuel = 'gas' AND day < '2021-01-01';
    IF e IS DISTINCT FROM (11240 + 35) * 0.5 THEN
        RAISE EXCEPTION 'generation_mix_daily: expected gas energy % MWh, got %', (11240 + 35) * 0.5, e;
    END IF;

    SELECT count(*) INTO n FROM carbon_intensity_daily WHERE region = 'GB' AND day < '2021-01-01';
    IF n <> 1 THEN RAISE EXCEPTION 'carbon_intensity_daily: expected the new actual to be visible in real time'; END IF;

    RAISE NOTICE 'PASS  daily aggregates use London days, sum gas across source codes, and include unrefreshed rows';
END $$;

-- ------------------------------------------------------------ benchmark views (V6, ADR-0010)
-- Two NDF revisions for 27 Oct 2020 18:00: one published at 12:47 on the 26th (before the 13:00 cut-off),
-- one at 15:00 (after it). The day-ahead view must pick the 12:47 one.
INSERT INTO demand_forecast VALUES
    (gen_random_uuid(), '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z', '2020-10-27', 37,
     '2020-10-26T12:47:00Z', now(), 'elexon-insights', 'ndf', 'GB', '1.1.0', 'ND', 31000, 'NDF'),
    (gen_random_uuid(), '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z', '2020-10-27', 37,
     '2020-10-26T15:00:00Z', now(), 'elexon-insights', 'ndf', 'GB', '1.1.0', 'ND', 32000, 'NDF');
-- The same half-hour measured twice: Elexon's initial outturn and NESO's settled figure.
INSERT INTO demand VALUES
    (gen_random_uuid(), '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z', '2020-10-27', 37,
     NULL, now(), 'elexon-insights', 'indo', 'GB', '1.1.0', 'INDO', 31500),
    (gen_random_uuid(), '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z', '2020-10-27', 37,
     NULL, now(), 'neso-data-portal', 'nd', 'GB', '1.1.0', 'ND', 31400);

DO $$
DECLARE mw double precision; src text;
BEGIN
    SELECT forecast_mw INTO mw FROM ndf_day_ahead WHERE valid_from = '2020-10-27T18:00:00Z';
    IF mw IS DISTINCT FROM 31000 THEN
        RAISE EXCEPTION 'ndf_day_ahead: expected the 12:47 revision (31000), got %', mw;
    END IF;
    SELECT demand_mw, source INTO mw, src FROM national_demand_actual WHERE valid_from = '2020-10-27T18:00:00Z';
    IF mw IS DISTINCT FROM 31400 OR src <> 'neso-data-portal' THEN
        RAISE EXCEPTION 'national_demand_actual: expected NESO settled ND (31400), got % from %', mw, src;
    END IF;
    RAISE NOTICE 'PASS  day-ahead benchmark takes the last revision before 13:00 D-1; actuals prefer settled ND';
END $$;

-- ------------------------------------------------------------ model forecasts (V7)
INSERT INTO model_forecast (task, model, model_version, issued_at, valid_from, valid_to, unit, p10, p50, p90) VALUES
    ('demand', 'lightgbm-cqr', 'abc1234', '2020-10-26T12:00:00Z', '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z',
     'MW', 30000, 31000, 32000),
    ('demand', 'lightgbm-cqr', 'abc1234', '2020-10-27T06:00:00Z', '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z',
     'MW', 30500, 31200, 31900);

DO $$
DECLARE mw double precision;
BEGIN
    SELECT p50 INTO mw FROM model_forecast_latest WHERE task = 'demand' AND valid_from = '2020-10-27T18:00:00Z';
    IF mw IS DISTINCT FROM 31200 THEN
        RAISE EXCEPTION 'model_forecast_latest: expected the 06:00 issue (31200), got %', mw;
    END IF;
    BEGIN
        INSERT INTO model_forecast (task, model, model_version, issued_at, valid_from, valid_to, unit, p10, p50, p90)
        VALUES ('carbon', 'm', 'v', '2020-10-27T06:00:00Z', '2020-10-27T18:00:00Z', '2020-10-27T18:30:00Z',
                'gCO2/kWh', 200, 150, 250);
        RAISE EXCEPTION 'a P10 above the P50 was accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    RAISE NOTICE 'PASS  the latest view picks the newest issue per half-hour; crossed quantiles are rejected';
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

SET LOCAL ROLE forecast_writer;
DO $$
BEGIN
    UPDATE model_forecast SET p50 = p50 WHERE task = 'demand';
    BEGIN
        DELETE FROM demand;
        RAISE EXCEPTION 'forecast_writer was allowed to touch demand';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    RAISE NOTICE 'PASS  forecast_writer can write model forecasts and nothing else';
END $$;
RESET ROLE;

SET LOCAL ROLE grid_writer;
DO $$
BEGIN
    BEGIN
        DELETE FROM model_forecast;
        RAISE EXCEPTION 'grid_writer was allowed to delete model forecasts';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    UPDATE demand SET demand_mw = demand_mw WHERE demand_type = 'INDO';
    DELETE FROM generation_mix WHERE event_id = '1f911f21-645e-5af7-b82c-ce53550d2bdb';
    BEGIN
        CREATE TABLE should_not_exist (id int);
        RAISE EXCEPTION 'grid_writer was allowed to create a table';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    RAISE NOTICE 'PASS  grid_writer can write event rows, not model forecasts, and cannot change the schema';
END $$;
RESET ROLE;

ROLLBACK;
