-- Daily rollups for the dashboard (ADR-0005).
-- Days are Europe/London days, so a clock-change day is one 23- or 25-hour bucket.
-- materialized_only = false: queries also see raw rows newer than the last refresh.
-- WITH NO DATA: creation can then run inside Flyway's transaction; the refresh policy fills them.

CREATE MATERIALIZED VIEW carbon_intensity_daily
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT
    time_bucket(INTERVAL '1 day', valid_from, 'Europe/London') AS day,
    region,
    avg(actual_gco2_per_kwh)  AS avg_gco2_per_kwh,
    min(actual_gco2_per_kwh)  AS min_gco2_per_kwh,
    max(actual_gco2_per_kwh)  AS max_gco2_per_kwh,
    count(*)                  AS periods
FROM carbon_intensity
WHERE issued_at IS NULL AND actual_gco2_per_kwh IS NOT NULL
GROUP BY day, region
WITH NO DATA;

CREATE MATERIALIZED VIEW demand_daily
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT
    time_bucket(INTERVAL '1 day', valid_from, 'Europe/London') AS day,
    demand_type,
    avg(demand_mw)                         AS avg_mw,
    min(demand_mw)                         AS min_mw,
    max(demand_mw)                         AS max_mw,
    -- each row is a half-hour, so MW x 0.5 h = MWh
    sum(demand_mw * 0.5)                   AS energy_mwh,
    count(*)                               AS periods
FROM demand
GROUP BY day, demand_type
WITH NO DATA;

-- Energy rather than average MW: a fuel can have several source codes per half-hour
-- (gas = CCGT + OCGT), so averaging rows would be wrong, while summing energy is not.
CREATE MATERIALIZED VIEW generation_mix_daily
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT
    time_bucket(INTERVAL '1 day', valid_from, 'Europe/London') AS day,
    region,
    fuel,
    sum(value * 0.5) AS energy_mwh
FROM generation_mix
WHERE unit = 'MW'
GROUP BY day, region, fuel
WITH NO DATA;

-- Refresh the last 30 days every 30 minutes. The window is much shorter than raw retention
-- (V4), so dropping old raw chunks never deletes aggregated history. Backfills outside the
-- window call refresh_continuous_aggregate explicitly.
SELECT add_continuous_aggregate_policy('carbon_intensity_daily',
    start_offset => INTERVAL '30 days', end_offset => INTERVAL '1 hour', schedule_interval => INTERVAL '30 minutes');
SELECT add_continuous_aggregate_policy('demand_daily',
    start_offset => INTERVAL '30 days', end_offset => INTERVAL '1 hour', schedule_interval => INTERVAL '30 minutes');
SELECT add_continuous_aggregate_policy('generation_mix_daily',
    start_offset => INTERVAL '30 days', end_offset => INTERVAL '1 hour', schedule_interval => INTERVAL '30 minutes');
