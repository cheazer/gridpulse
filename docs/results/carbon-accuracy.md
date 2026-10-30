# Day-ahead carbon intensity forecast accuracy

Generated 2026-10-30 by `python -m gridpulse_forecasting backtest --task carbon --model lightgbm-cqr --report ...` (MLflow run `1de4a13c1ba74f7286032724ab13aac3`).

Rolling-origin backtest over **2025-10 to 2026-09** (12 months): a fresh model each month, fitted on what was known at the month's first issue time, forecasting each day as of **13:00 UK time the day before** (ADR-0010, ADR-0011). All forecasts are scored on the same **17,518 half-hours**. Actuals are NESO's national (GB) carbon intensity. **The NESO benchmark is not a day-ahead forecast**: it is NESO's final forecast for each half-hour, made shortly before it, the only NESO forecast recorded for this period (day-ahead revisions are recorded from late October 2026). It knew far more than a day-ahead model can, so it is a ceiling, not a like-for-like comparison. MAE is the headline metric: carbon intensity can fall close to zero, where percentage errors explode.

## Headline

| Forecast | MAE | MAPE | RMSE | Bias |
| --- | ---: | ---: | ---: | ---: |
| GridPulse | 23.2 gCO2/kWh | 21.83% | 29.6 gCO2/kWh | -1.7 gCO2/kWh |
| NESO final (not day-ahead) | 9.9 gCO2/kWh | 9.44% | 14.3 gCO2/kWh | +0.1 gCO2/kWh |
| Seasonal naive | 54.3 gCO2/kWh | 53.62% | 68.8 gCO2/kWh | +0.7 gCO2/kWh |

Bias is the mean of forecast minus actual: negative means forecasts were too low on average.

## Prediction intervals

| P10-P90 coverage (target 80%) | Mean width | Pinball P10 | Pinball P50 | Pinball P90 |
| --- | ---: | ---: | ---: | ---: |
| 80.2% | 73.7 gCO2/kWh | 4.8 gCO2/kWh | 11.6 gCO2/kWh | 5.5 gCO2/kWh |

## MAE by month

| Month | GridPulse | NESO final (not day-ahead) | Seasonal naive |
| --- | ---: | ---: | ---: |
| 2025-10 | 28.0 gCO2/kWh | 8.5 gCO2/kWh | 83.3 gCO2/kWh |
| 2025-11 | 22.9 gCO2/kWh | 10.3 gCO2/kWh | 62.0 gCO2/kWh |
| 2025-12 | 22.2 gCO2/kWh | 9.0 gCO2/kWh | 48.0 gCO2/kWh |
| 2026-01 | 23.0 gCO2/kWh | 8.6 gCO2/kWh | 63.5 gCO2/kWh |
| 2026-02 | 19.9 gCO2/kWh | 8.5 gCO2/kWh | 53.5 gCO2/kWh |
| 2026-03 | 20.2 gCO2/kWh | 9.8 gCO2/kWh | 70.3 gCO2/kWh |
| 2026-04 | 23.3 gCO2/kWh | 9.1 gCO2/kWh | 39.7 gCO2/kWh |
| 2026-05 | 22.4 gCO2/kWh | 9.3 gCO2/kWh | 48.5 gCO2/kWh |
| 2026-06 | 27.8 gCO2/kWh | 9.2 gCO2/kWh | 40.6 gCO2/kWh |
| 2026-07 | 23.7 gCO2/kWh | 9.4 gCO2/kWh | 43.3 gCO2/kWh |
| 2026-08 | 25.3 gCO2/kWh | 12.4 gCO2/kWh | 45.9 gCO2/kWh |
| 2026-09 | 19.0 gCO2/kWh | 14.7 gCO2/kWh | 52.2 gCO2/kWh |

## MAE by time of day (UK)

| UK time | GridPulse | NESO final (not day-ahead) | Seasonal naive |
| --- | ---: | ---: | ---: |
| 00:00-04:00 | 24.3 gCO2/kWh | 7.6 gCO2/kWh | 55.4 gCO2/kWh |
| 04:00-08:00 | 25.4 gCO2/kWh | 11.8 gCO2/kWh | 59.7 gCO2/kWh |
| 08:00-12:00 | 22.2 gCO2/kWh | 8.9 gCO2/kWh | 53.2 gCO2/kWh |
| 12:00-16:00 | 20.5 gCO2/kWh | 7.6 gCO2/kWh | 49.3 gCO2/kWh |
| 16:00-20:00 | 21.8 gCO2/kWh | 11.7 gCO2/kWh | 53.1 gCO2/kWh |
| 20:00-24:00 | 24.8 gCO2/kWh | 11.7 gCO2/kWh | 55.2 gCO2/kWh |

## What the model leans on (2026-09 model)

Share of the median model's total split gain.

| Feature | Share |
| --- | ---: |
| `wind_speed_100m_ms` | 26.5% |
| `carbon_latest` | 7.8% |
| `demand_lag_7d` | 7.2% |
| `solar_radiation_wm2` | 6.6% |
| `carbon_mean_24h` | 5.1% |
| `day_of_year_sin` | 4.9% |
| `demand_latest` | 4.8% |
| `day_of_year_cos` | 4.8% |
| `demand_mean_24h` | 4.6% |
| `lead_hours` | 3.5% |
