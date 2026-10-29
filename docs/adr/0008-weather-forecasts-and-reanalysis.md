# 0008. Weather: forecast revisions and reanalysis, kept apart

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

Temperature drives demand (heating), and wind and sunshine drive carbon intensity, so the Phase 2 models need
weather features. Open-Meteo (CC BY 4.0, free for non-commercial use, no key) offers two relevant APIs:

- **Forecast API:** hourly forecasts for the next days, plus earlier hours today from the same model run.
- **Archive API:** hourly *reanalysis*, the best estimate of what the weather actually was, a day or more
  behind real time.

The trap is **training/serving skew**. At prediction time a model only has *forecast* weather, which is less
accurate than what eventually happened. A model trained on reanalysis looks better in a backtest than it will
ever be live.

## Decision

**Two datasets, never mixed:**

| Dataset | Endpoint | Poll | Event | `issued_at` |
|---|---|---|---|---|
| Forecasts | `api.open-meteo.com/v1/forecast` | hourly at :20, plus start-up | hours starting after the poll | poll time bucketed to **6 h** (global model runs are 6-hourly) |
| Reanalysis | `archive-api.open-meteo.com/v1/archive` | daily at 06:40 UTC, plus start-up | the last 10 complete days | null |

- Elapsed hours from the forecast API are **not** stored as observations. They are model output, not
  measurements, and storing them under the same identity as reanalysis would let the two overwrite each other.
  "What happened" means reanalysis only, consistently for training and evaluation.
- Phase 2 trains on **forecast-era features where it can**. Live forecasts accumulate from now on, and the
  backfill (step 1.8) will use Open-Meteo's historical-forecast archive for the past. Reanalysis provides the
  ground truth and the comparison.

**Locations:** eight, chosen for both demand (population) and generation (wind in the north, solar in the
east). Each maps to a Carbon Intensity region, its `series` is the location id, and its coordinates are
configured rather than taken from the response (Open-Meteo snaps them to its grid):

| Location | Region | Why |
|---|---|---|
| london | london | demand |
| birmingham | west-midlands | demand |
| manchester | north-west-england | demand, west-coast wind |
| leeds | yorkshire | demand |
| edinburgh | south-scotland | demand, Scottish wind |
| cardiff | south-wales | demand, south-west wind |
| norwich | east-england | solar, North Sea wind |
| aberdeen | north-scotland | offshore wind |

**Units and conventions:** wind in m/s (`wind_speed_unit=ms`), times as Unix epoch (`timeformat=unixtime`), so
there is no time-zone ambiguity. An event covers `[t, t + 1 h)`. Temperature, wind and cloud cover are
instantaneous values at `t`. **Shortwave radiation is Open-Meteo's mean over the hour *ending* at `t`**, so
Phase 2 feature engineering must shift it rather than treat it as concurrent.

**Rate limits:** Open-Meteo counts each location as a call: 600/minute, 5,000/hour, 10,000/day. Live polling
uses about 200 a day. Each host gets its own `HttpSource` limited to 1 request/s.

## Consequences

- Weather forecasts are stored per 6-hour revision, about 2,300 rows a day, so a backtest can ask what the
  forecast was at any point.
- Reanalysis lags by about a day, so it can't be used for the newest periods. That's fine because it's only
  needed after the fact.
- Each request carries all eight locations, so one poll is one HTTP request per dataset.
