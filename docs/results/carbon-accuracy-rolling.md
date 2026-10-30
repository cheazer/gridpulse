# Rolling 48-hour carbon intensity forecast accuracy

Generated 2026-10-30 by `python -m gridpulse_forecasting backtest --task carbon --model lightgbm-cqr --mode rolling --report ...` (MLflow run `0e50f2d0fe2249c4b33cc9e3fe90e336`).

Rolling-origin backtest over **2025-10 to 2026-09** (12 months): a fresh model each month, fitted on what was known at the month's first issue time, issuing forecasts **every 6 hours (01:00, 07:00, 13:00, 19:00 UK) for the next 48 hours**, as the forecast job does. Each half-hour is forecast several times, at different lead times. (ADR-0010, ADR-0011.) All forecasts are scored on the same **140,144 forecasts**. Actuals are NESO's national (GB) carbon intensity. **The NESO benchmark is not a day-ahead forecast**: it is NESO's final forecast for each half-hour, made shortly before it, the only NESO forecast recorded for this period (day-ahead revisions are recorded from late October 2026). It knew far more than a day-ahead model can, so it is a ceiling, not a like-for-like comparison. MAE is the headline metric: carbon intensity can fall close to zero, where percentage errors explode.

## Headline

| Forecast | MAE | MAPE | RMSE | Bias |
| --- | ---: | ---: | ---: | ---: |
| GridPulse | 23.6 gCO2/kWh | 22.65% | 30.2 gCO2/kWh | -0.1 gCO2/kWh |
| NESO final (not day-ahead) | 9.9 gCO2/kWh | 9.44% | 14.3 gCO2/kWh | +0.1 gCO2/kWh |
| Seasonal naive | 54.3 gCO2/kWh | 53.62% | 68.8 gCO2/kWh | +0.7 gCO2/kWh |

Bias is the mean of forecast minus actual: negative means forecasts were too low on average.

## Prediction intervals

| P10-P90 coverage (target 80%) | Mean width | Pinball P10 | Pinball P50 | Pinball P90 |
| --- | ---: | ---: | ---: | ---: |
| 78.6% | 73.4 gCO2/kWh | 4.9 gCO2/kWh | 11.8 gCO2/kWh | 5.6 gCO2/kWh |

## MAE by month

| Month | GridPulse | NESO final (not day-ahead) | Seasonal naive |
| --- | ---: | ---: | ---: |
| 2025-10 | 28.8 gCO2/kWh | 8.5 gCO2/kWh | 83.4 gCO2/kWh |
| 2025-11 | 23.1 gCO2/kWh | 10.3 gCO2/kWh | 62.0 gCO2/kWh |
| 2025-12 | 21.1 gCO2/kWh | 9.0 gCO2/kWh | 48.0 gCO2/kWh |
| 2026-01 | 21.5 gCO2/kWh | 8.6 gCO2/kWh | 63.5 gCO2/kWh |
| 2026-02 | 21.0 gCO2/kWh | 8.5 gCO2/kWh | 53.5 gCO2/kWh |
| 2026-03 | 24.0 gCO2/kWh | 9.9 gCO2/kWh | 70.3 gCO2/kWh |
| 2026-04 | 25.2 gCO2/kWh | 9.1 gCO2/kWh | 39.7 gCO2/kWh |
| 2026-05 | 21.9 gCO2/kWh | 9.3 gCO2/kWh | 48.5 gCO2/kWh |
| 2026-06 | 27.9 gCO2/kWh | 9.2 gCO2/kWh | 40.6 gCO2/kWh |
| 2026-07 | 24.5 gCO2/kWh | 9.4 gCO2/kWh | 43.3 gCO2/kWh |
| 2026-08 | 24.4 gCO2/kWh | 12.4 gCO2/kWh | 45.9 gCO2/kWh |
| 2026-09 | 20.1 gCO2/kWh | 14.7 gCO2/kWh | 52.2 gCO2/kWh |

## MAE by time of day (UK)

| UK time | GridPulse | NESO final (not day-ahead) | Seasonal naive |
| --- | ---: | ---: | ---: |
| 00:00-04:00 | 25.5 gCO2/kWh | 7.6 gCO2/kWh | 55.4 gCO2/kWh |
| 04:00-08:00 | 26.1 gCO2/kWh | 11.8 gCO2/kWh | 59.7 gCO2/kWh |
| 08:00-12:00 | 23.3 gCO2/kWh | 8.9 gCO2/kWh | 53.2 gCO2/kWh |
| 12:00-16:00 | 20.8 gCO2/kWh | 7.6 gCO2/kWh | 49.3 gCO2/kWh |
| 16:00-20:00 | 22.1 gCO2/kWh | 11.7 gCO2/kWh | 53.1 gCO2/kWh |
| 20:00-24:00 | 24.0 gCO2/kWh | 11.7 gCO2/kWh | 55.2 gCO2/kWh |

## MAE by lead time

How far ahead the forecast was issued. NESO's forecast is the same whatever the lead (its 13:00 day-ahead or final forecast), so only GridPulse's column should change down the table.

| Lead | GridPulse | NESO final (not day-ahead) | Seasonal naive |
| --- | ---: | ---: | ---: |
| 0-6 h | 21.7 gCO2/kWh | 9.9 gCO2/kWh | 54.3 gCO2/kWh |
| 6-12 h | 23.0 gCO2/kWh | 9.9 gCO2/kWh | 54.3 gCO2/kWh |
| 12-24 h | 23.6 gCO2/kWh | 9.9 gCO2/kWh | 54.3 gCO2/kWh |
| 24-36 h | 24.2 gCO2/kWh | 9.9 gCO2/kWh | 54.3 gCO2/kWh |
| 36-48 h | 24.4 gCO2/kWh | 9.9 gCO2/kWh | 54.3 gCO2/kWh |

## What the model leans on (2026-09 model)

Share of the median model's total split gain.

| Feature | Share |
| --- | ---: |
| `wind_speed_100m_ms` | 30.3% |
| `demand_lag_7d` | 8.4% |
| `solar_radiation_wm2` | 8.0% |
| `day_of_year_sin` | 7.4% |
| `day_of_year_cos` | 6.9% |
| `day_of_week` | 4.4% |
| `half_hour_of_day` | 4.4% |
| `carbon_latest` | 3.8% |
| `carbon_lag_14d` | 3.1% |
| `carbon_lag_7d` | 3.1% |
