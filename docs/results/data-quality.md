# GridPulse data-quality report

Generated 2026-10-29 17:03 UTC by `tools/data-quality/report.py`. Times are UK local. Regenerate after loading data; numbers below are from the run that produced this file.

## Coverage of half-hourly series

Each series is checked from its own first to last half-hour, so NESO's publication lag isn't counted as a gap.

| Series | First | Last | Present | Missing | Gaps | Complete |
|---|---|---|---|---|---|---|
| Carbon intensity, national actuals | 2024-10-28 23:30 | 2026-10-29 16:00 | 35,074 | 0 | 0 | 100.000% |
| Demand INDO (Elexon, initial) | 2024-10-29 00:00 | 2026-10-29 16:00 | 35,073 | 0 | 0 | 100.000% |
| Demand ITSDO (Elexon, initial) | 2024-10-29 00:00 | 2026-10-29 16:00 | 35,073 | 0 | 0 | 100.000% |
| Demand ND (NESO, settled) | 2024-10-29 00:00 | 2026-10-08 23:30 | 34,078 | 0 | 0 | 100.000% |
| Demand TSD (NESO, settled) | 2024-10-29 00:00 | 2026-10-08 23:30 | 34,078 | 0 | 0 | 100.000% |
| Generation by fuel (FUELHH) | 2024-10-29 00:00 | 2026-10-29 16:00 | 35,073 | 0 | 0 | 100.000% |
| Embedded solar and wind (NESO) | 2024-10-29 00:00 | 2026-10-08 23:30 | 34,078 | 0 | 0 | 100.000% |
| System prices | 2024-10-29 00:00 | 2026-10-29 16:00 | 35,073 | 0 | 0 | 100.000% |
| NESO day-ahead forecast (13:00 D-1) | 2024-10-30 00:00 | 2026-10-31 04:30 | 35,098 | 0 | 0 | 100.000% |

## Weather coverage

| Location | Kind | First | Last | Hours | Missing | Complete |
|---|---|---|---|---|---|---|
| aberdeen | forecast | 2024-10-29 00:00 | 2026-10-31 23:00 | 17,580 | 12 | 99.93% |
| birmingham | forecast | 2024-10-29 00:00 | 2026-10-31 23:00 | 17,580 | 12 | 99.93% |
| cardiff | forecast | 2024-10-29 00:00 | 2026-10-31 23:00 | 17,580 | 12 | 99.93% |
| edinburgh | forecast | 2024-10-29 00:00 | 2026-10-31 23:00 | 17,580 | 12 | 99.93% |
| leeds | forecast | 2024-10-29 00:00 | 2026-10-31 23:00 | 17,580 | 12 | 99.93% |
| london | forecast | 2024-10-29 00:00 | 2026-10-31 23:00 | 17,580 | 12 | 99.93% |
| manchester | forecast | 2024-10-29 00:00 | 2026-10-31 23:00 | 17,580 | 12 | 99.93% |
| norwich | forecast | 2024-10-29 00:00 | 2026-10-31 23:00 | 17,580 | 12 | 99.93% |
| aberdeen | reanalysis | 2024-10-29 00:00 | 2026-10-28 23:00 | 17,520 | 0 | 100.00% |
| birmingham | reanalysis | 2024-10-29 00:00 | 2026-10-28 23:00 | 17,520 | 0 | 100.00% |
| cardiff | reanalysis | 2024-10-29 00:00 | 2026-10-28 23:00 | 17,520 | 0 | 100.00% |
| edinburgh | reanalysis | 2024-10-29 00:00 | 2026-10-28 23:00 | 17,520 | 0 | 100.00% |
| leeds | reanalysis | 2024-10-29 00:00 | 2026-10-28 23:00 | 17,520 | 0 | 100.00% |
| london | reanalysis | 2024-10-29 00:00 | 2026-10-28 23:00 | 17,520 | 0 | 100.00% |
| manchester | reanalysis | 2024-10-29 00:00 | 2026-10-28 23:00 | 17,520 | 0 | 100.00% |
| norwich | reanalysis | 2024-10-29 00:00 | 2026-10-28 23:00 | 17,520 | 0 | 100.00% |

## Duplicates

Records sharing a logical key under different event IDs. Should all be zero (ADR-0004).

| Table | Logical key | Duplicated keys |
|---|---|---|
| carbon_intensity | source, series, region, valid_from, issued_at | 0 |
| demand | source, series, region, valid_from, issued_at | 0 |
| demand_forecast | source, series, region, valid_from, issued_at | 0 |
| generation_mix | source, series, region, valid_from, issued_at, source_fuel_code | 0 |
| system_price | source, series, region, valid_from, issued_at | 0 |
| weather | source, series, region, valid_from, issued_at | 0 |

## Clock-change days

A UK settlement day has 46 periods when the clocks go forward and 50 when they go back.

| Settlement date | Expected | INDO | ND | Carbon intensity | Result |
|---|---|---|---|---|---|
| 2025-03-30 | 46 | 46 | 46 | 46 | ok |
| 2025-10-26 | 50 | 50 | 50 | 50 | ok |
| 2026-03-29 | 46 | 46 | 46 | 46 | ok |
| 2026-10-25 | 50 | 50 |  | 50 | ok |

## Value ranges

| Measure | Unit | Min | Mean | Max |
|---|---|---|---|---|
| Carbon intensity actual | gCO2/kWh | 0.0 | 127.7 | 303.0 |
| Demand indo | MW | 12,621.0 | 26,111.3 | 47,319.0 |
| Demand itsdo | MW | 15,255.0 | 28,605.5 | 50,042.0 |
| Demand nd | MW | 12,626.0 | 26,135.9 | 47,382.0 |
| Demand tsd | MW | 13,803.0 | 28,510.0 | 50,101.0 |
| Embedded solar | MW | 0.0 | 2,269.4 | 16,393.0 |
| Net imports (sum of interconnectors) | MW | -7,516.0 | 3,160.6 | 9,230.0 |
| System sell price | £/MWh | -97.9 | 92.3 | 2,900.0 |
| Temperature (reanalysis) | °C | -7.8 | 11.3 | 36.2 |

## Agreement between sources

| Comparison | Half-hours | Mean difference (MW) | MAE (MW) | MAPE | Within 2% |
|---|---|---|---|---|---|
| INDO (initial) vs ND (settled) | 34,078 | -36.9 | 63.0 | 0.23% | 99.5% |

## The day-ahead benchmark (ADR-0010)

NESO's day-ahead National Demand forecast, as published by 13:00 UK time the day before, against actual ND (settled where NESO has published it, otherwise INDO). A seasonal-naive forecast on the same half-hours is the floor any model must clear. **This is the number Phase 2 has to beat.**

| Forecast | Half-hours | Bias (MW) | MAE (MW) | RMSE (MW) | MAPE |
|---|---|---|---|---|---|
| NESO NDF, published by 13:00 D-1 | 34,737 | -30.7 | 685.2 | 918.8 | 2.74% |
| Seasonal naive (same half-hour last week) | 34,737 | 8.1 | 2,159.2 | 2,923.7 | 8.45% |

### By month

| Month | Half-hours | NESO NDF MAPE | Seasonal-naive MAPE |
|---|---|---|---|
| 2024-11 | 1,248 | 1.97% | 6.47% |
| 2024-12 | 1,488 | 2.22% | 8.76% |
| 2025-01 | 1,488 | 2.12% | 11.12% |
| 2025-02 | 1,344 | 2.34% | 8.50% |
| 2025-03 | 1,486 | 3.02% | 11.42% |
| 2025-04 | 1,440 | 2.69% | 7.97% |
| 2025-05 | 1,488 | 3.10% | 7.75% |
| 2025-06 | 1,440 | 2.88% | 7.76% |
| 2025-07 | 1,488 | 2.61% | 7.47% |
| 2025-08 | 1,488 | 2.74% | 8.47% |
| 2025-09 | 1,440 | 2.81% | 7.59% |
| 2025-10 | 1,490 | 2.63% | 7.63% |
| 2025-11 | 1,440 | 2.09% | 7.91% |
| 2025-12 | 1,488 | 2.01% | 7.55% |
| 2026-01 | 1,488 | 2.09% | 9.19% |
| 2026-02 | 1,344 | 2.17% | 7.20% |
| 2026-03 | 1,486 | 3.42% | 8.15% |
| 2026-04 | 1,440 | 3.16% | 11.49% |
| 2026-05 | 1,488 | 3.31% | 8.63% |
| 2026-06 | 1,440 | 3.99% | 8.97% |
| 2026-07 | 1,488 | 3.40% | 8.24% |
| 2026-08 | 1,488 | 2.78% | 7.92% |
| 2026-09 | 1,440 | 3.08% | 7.51% |
| 2026-10 | 1,379 | 2.97% | 8.61% |
