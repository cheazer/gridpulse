-- One typed hypertable per event type (ADR-0005).
-- Envelope columns are repeated in each table on purpose: explicit beats clever in DDL.
-- Primary key (event_id, valid_from): the sink upserts on it (ADR-0004), and hypertable
-- unique indexes must include the partitioning column.

-- ---------------------------------------------------------------- carbon intensity
CREATE TABLE carbon_intensity (
    event_id              uuid        NOT NULL,
    valid_from            timestamptz NOT NULL,
    valid_to              timestamptz NOT NULL,
    settlement_date       date,
    settlement_period     smallint    CHECK (settlement_period BETWEEN 1 AND 50),
    issued_at             timestamptz,
    ingested_at           timestamptz NOT NULL,
    source                text        NOT NULL,
    series                text        NOT NULL,
    region                text        NOT NULL,
    schema_version        text        NOT NULL,
    forecast_gco2_per_kwh smallint    CHECK (forecast_gco2_per_kwh BETWEEN 0 AND 2000),
    actual_gco2_per_kwh   smallint    CHECK (actual_gco2_per_kwh BETWEEN 0 AND 2000),
    intensity_index       text        CHECK (intensity_index IN ('very low', 'low', 'moderate', 'high', 'very high')),
    PRIMARY KEY (event_id, valid_from),
    CHECK (valid_to > valid_from),
    CHECK ((settlement_date IS NULL) = (settlement_period IS NULL)),
    CHECK (forecast_gco2_per_kwh IS NOT NULL OR actual_gco2_per_kwh IS NOT NULL)
);
SELECT create_hypertable('carbon_intensity', by_range('valid_from', INTERVAL '30 days'));
CREATE INDEX ON carbon_intensity (region, valid_from DESC);
-- "Latest forecast revision issued before time T" for backtests and the heatmap.
CREATE INDEX ON carbon_intensity (region, valid_from, issued_at DESC) WHERE issued_at IS NOT NULL;

-- ---------------------------------------------------------------- generation mix (long: one row per fuel code)
CREATE TABLE generation_mix (
    event_id          uuid             NOT NULL,
    valid_from        timestamptz      NOT NULL,
    valid_to          timestamptz      NOT NULL,
    settlement_date   date,
    settlement_period smallint         CHECK (settlement_period BETWEEN 1 AND 50),
    issued_at         timestamptz,
    ingested_at       timestamptz      NOT NULL,
    source            text             NOT NULL,
    series            text             NOT NULL,
    region            text             NOT NULL,
    schema_version    text             NOT NULL,
    unit              text             NOT NULL CHECK (unit IN ('MW', 'percent')),
    fuel              text             NOT NULL CHECK (fuel IN ('gas', 'coal', 'oil', 'nuclear', 'wind', 'solar', 'hydro',
                                                                'biomass', 'storage', 'imports', 'other')),
    source_fuel_code  text             NOT NULL,
    value             double precision NOT NULL CHECK (value >= 0),
    PRIMARY KEY (event_id, source_fuel_code, valid_from),
    CHECK (valid_to > valid_from),
    CHECK ((settlement_date IS NULL) = (settlement_period IS NULL))
);
SELECT create_hypertable('generation_mix', by_range('valid_from', INTERVAL '30 days'));
CREATE INDEX ON generation_mix (fuel, valid_from DESC);
CREATE INDEX ON generation_mix (region, valid_from DESC);

-- ---------------------------------------------------------------- demand outturn
CREATE TABLE demand (
    event_id          uuid             NOT NULL,
    valid_from        timestamptz      NOT NULL,
    valid_to          timestamptz      NOT NULL,
    settlement_date   date,
    settlement_period smallint         CHECK (settlement_period BETWEEN 1 AND 50),
    issued_at         timestamptz,
    ingested_at       timestamptz      NOT NULL,
    source            text             NOT NULL,
    series            text             NOT NULL,
    region            text             NOT NULL,
    schema_version    text             NOT NULL,
    demand_type       text             NOT NULL CHECK (demand_type IN ('ND', 'TSD', 'INDO', 'ITSDO')),
    demand_mw         double precision NOT NULL CHECK (demand_mw >= 0),
    PRIMARY KEY (event_id, valid_from),
    CHECK (valid_to > valid_from),
    CHECK ((settlement_date IS NULL) = (settlement_period IS NULL))
);
SELECT create_hypertable('demand', by_range('valid_from', INTERVAL '30 days'));
CREATE INDEX ON demand (demand_type, valid_from DESC);

-- ---------------------------------------------------------------- third-party demand forecasts (every revision)
CREATE TABLE demand_forecast (
    event_id          uuid             NOT NULL,
    valid_from        timestamptz      NOT NULL,
    valid_to          timestamptz      NOT NULL,
    settlement_date   date,
    settlement_period smallint         CHECK (settlement_period BETWEEN 1 AND 50),
    issued_at         timestamptz      NOT NULL,
    ingested_at       timestamptz      NOT NULL,
    source            text             NOT NULL,
    series            text             NOT NULL,
    region            text             NOT NULL,
    schema_version    text             NOT NULL,
    demand_type       text             NOT NULL CHECK (demand_type IN ('ND', 'TSD', 'INDO', 'ITSDO')),
    forecast_mw       double precision NOT NULL CHECK (forecast_mw >= 0),
    product           text             NOT NULL,
    PRIMARY KEY (event_id, valid_from),
    CHECK (valid_to > valid_from),
    CHECK ((settlement_date IS NULL) = (settlement_period IS NULL))
);
SELECT create_hypertable('demand_forecast', by_range('valid_from', INTERVAL '30 days'));
CREATE INDEX ON demand_forecast (product, demand_type, valid_from, issued_at DESC);

-- ---------------------------------------------------------------- system (imbalance) prices
CREATE TABLE system_price (
    event_id                      uuid          NOT NULL,
    valid_from                    timestamptz   NOT NULL,
    valid_to                      timestamptz   NOT NULL,
    settlement_date               date,
    settlement_period             smallint      CHECK (settlement_period BETWEEN 1 AND 50),
    issued_at                     timestamptz,
    ingested_at                   timestamptz   NOT NULL,
    source                        text          NOT NULL,
    series                        text          NOT NULL,
    region                        text          NOT NULL,
    schema_version                text          NOT NULL,
    -- numeric, not float: these are money. Prices can be negative.
    system_sell_price_gbp_per_mwh numeric(10, 2) NOT NULL,
    system_buy_price_gbp_per_mwh  numeric(10, 2) NOT NULL,
    net_imbalance_volume_mwh      numeric(12, 3),
    PRIMARY KEY (event_id, valid_from),
    CHECK (valid_to > valid_from),
    CHECK ((settlement_date IS NULL) = (settlement_period IS NULL))
);
SELECT create_hypertable('system_price', by_range('valid_from', INTERVAL '30 days'));

-- ---------------------------------------------------------------- weather (observations and forecasts; series = location id)
CREATE TABLE weather (
    event_id                uuid             NOT NULL,
    valid_from              timestamptz      NOT NULL,
    valid_to                timestamptz      NOT NULL,
    settlement_date         date,
    settlement_period       smallint         CHECK (settlement_period BETWEEN 1 AND 50),
    issued_at               timestamptz,
    ingested_at             timestamptz      NOT NULL,
    source                  text             NOT NULL,
    series                  text             NOT NULL,
    region                  text             NOT NULL,
    schema_version          text             NOT NULL,
    latitude                double precision NOT NULL,
    longitude               double precision NOT NULL,
    temperature_2m_c        real,
    wind_speed_10m_ms       real             CHECK (wind_speed_10m_ms >= 0),
    wind_speed_100m_ms      real             CHECK (wind_speed_100m_ms >= 0),
    shortwave_radiation_wm2 real             CHECK (shortwave_radiation_wm2 >= 0),
    cloud_cover_pct         real             CHECK (cloud_cover_pct BETWEEN 0 AND 100),
    PRIMARY KEY (event_id, valid_from),
    CHECK (valid_to > valid_from),
    CHECK ((settlement_date IS NULL) = (settlement_period IS NULL))
);
SELECT create_hypertable('weather', by_range('valid_from', INTERVAL '30 days'));
CREATE INDEX ON weather (series, valid_from DESC);
CREATE INDEX ON weather (series, valid_from, issued_at DESC) WHERE issued_at IS NOT NULL;
