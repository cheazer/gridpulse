-- The day-ahead benchmark (ADR-0010), defined once in the database so the data-quality report and the
-- Phase 2 backtests can't define it differently.

-- NESO's day-ahead forecast: for each half-hour, the latest NDF revision published at or before 13:00 UK
-- time on the day before its settlement date.
CREATE VIEW ndf_day_ahead AS
SELECT DISTINCT ON (valid_from)
    valid_from,
    settlement_date,
    settlement_period,
    forecast_mw,
    issued_at
FROM demand_forecast
WHERE product = 'NDF'
  AND issued_at <= ((settlement_date - 1) + TIME '13:00') AT TIME ZONE 'Europe/London'
ORDER BY valid_from, issued_at DESC;

-- What NDF forecasts, measured: NESO's settled National Demand where published (it lags by weeks),
-- otherwise Elexon's initial outturn (INDO). Both use the ND definition (ADR-0007).
CREATE VIEW national_demand_actual AS
SELECT DISTINCT ON (valid_from)
    valid_from,
    settlement_date,
    settlement_period,
    demand_mw,
    source
FROM demand
WHERE (source = 'neso-data-portal' AND series = 'nd')
   OR (source = 'elexon-insights' AND series = 'indo')
ORDER BY valid_from, (source = 'neso-data-portal') DESC;
