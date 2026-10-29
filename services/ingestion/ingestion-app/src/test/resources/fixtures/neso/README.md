Real data from the NESO Data Portal (https://www.neso.energy/data-portal, NESO Open Data Licence), recorded 2026-10-29:

- package-show.json: GET /api/3/action/package_show?id=historic-demand-data, trimmed to the 2024-2026 resources
- demanddata_2024-sample.csv: 26-28 Oct 2024 from the 2024 file (old DD-MON-YYYY dates, no FORECAST_ACTUAL_INDICATOR,
  50 periods on the clock-change day)
- demanddataupdate_2026-sample.csv: 28-30 Mar 2026 from the 2026 file (ISO dates, indicator column, 46 periods)
- demanddata_2025-sample.csv: 30 Mar 2025 from the 2025 file (every value double-quoted, ISO dates, no indicator column, 46 periods)
