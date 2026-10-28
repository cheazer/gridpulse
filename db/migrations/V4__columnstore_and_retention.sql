-- Compression and retention (ADR-0005).
-- Columnstore: chunks older than 90 days are converted to compressed columnar form.
-- segmentby = the column queries filter on, so a query for one region only decompresses that segment.

ALTER TABLE carbon_intensity SET (timescaledb.enable_columnstore = true,
    timescaledb.segmentby = 'region', timescaledb.orderby = 'valid_from DESC');
ALTER TABLE generation_mix   SET (timescaledb.enable_columnstore = true,
    timescaledb.segmentby = 'fuel', timescaledb.orderby = 'valid_from DESC');
ALTER TABLE demand           SET (timescaledb.enable_columnstore = true,
    timescaledb.segmentby = 'demand_type', timescaledb.orderby = 'valid_from DESC');
ALTER TABLE demand_forecast  SET (timescaledb.enable_columnstore = true,
    timescaledb.segmentby = 'demand_type', timescaledb.orderby = 'valid_from DESC, issued_at DESC');
ALTER TABLE system_price     SET (timescaledb.enable_columnstore = true,
    timescaledb.segmentby = 'series', timescaledb.orderby = 'valid_from DESC');
ALTER TABLE weather          SET (timescaledb.enable_columnstore = true,
    timescaledb.segmentby = 'series', timescaledb.orderby = 'valid_from DESC');

CALL add_columnstore_policy('carbon_intensity', after => INTERVAL '90 days');
CALL add_columnstore_policy('generation_mix',   after => INTERVAL '90 days');
CALL add_columnstore_policy('demand',           after => INTERVAL '90 days');
CALL add_columnstore_policy('demand_forecast',  after => INTERVAL '90 days');
CALL add_columnstore_policy('system_price',     after => INTERVAL '90 days');
CALL add_columnstore_policy('weather',          after => INTERVAL '90 days');

-- Raw retention: 5 years. Training uses ~2 years; the rest is headroom. Daily aggregates are kept.
SELECT add_retention_policy('carbon_intensity', drop_after => INTERVAL '5 years');
SELECT add_retention_policy('generation_mix',   drop_after => INTERVAL '5 years');
SELECT add_retention_policy('demand',           drop_after => INTERVAL '5 years');
SELECT add_retention_policy('demand_forecast',  drop_after => INTERVAL '5 years');
SELECT add_retention_policy('system_price',     drop_after => INTERVAL '5 years');
SELECT add_retention_policy('weather',          drop_after => INTERVAL '5 years');
