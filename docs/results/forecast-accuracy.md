# Day-ahead demand forecast accuracy

Generated 2026-10-30 by `python -m gridpulse_forecasting backtest --task demand --model lightgbm-cqr --report ...` (MLflow run `8a271ef362e14d988e01e5a18dc2ef34`).

Rolling-origin backtest over **2025-10 to 2026-09** (12 months): a fresh model each month, fitted on what was known at the month's first issue time, forecasting each day as of **13:00 UK time the day before** (ADR-0010, ADR-0011). All forecasts are scored on the same **17,520 half-hours**. Actuals are NESO's settled national demand (ND), or Elexon's initial outturn where ND isn't published yet. NESO's forecast is its day-ahead NDF as published by 13:00 UK time the day before: the same information cut-off as ours (ADR-0010).

## Headline

| Forecast | MAPE | MAE | RMSE | Bias |
| --- | ---: | ---: | ---: | ---: |
| GridPulse | 4.23% | 1,062 MW | 1,415 MW | -138 MW |
| NESO day-ahead | 2.85% | 709 MW | 957 MW | -84 MW |
| Seasonal naive | 8.36% | 2,119 MW | 2,880 MW | -16 MW |

Bias is the mean of forecast minus actual: negative means forecasts were too low on average.

## Prediction intervals

| P10-P90 coverage (target 80%) | Mean width | Pinball P10 | Pinball P50 | Pinball P90 |
| --- | ---: | ---: | ---: | ---: |
| 79.7% | 3,684 MW | 269 MW | 531 MW | 263 MW |

## MAPE by month

| Month | GridPulse | NESO day-ahead | Seasonal naive |
| --- | ---: | ---: | ---: |
| 2025-10 | 3.63% | 2.63% | 7.62% |
| 2025-11 | 4.38% | 2.09% | 7.99% |
| 2025-12 | 4.45% | 2.01% | 7.49% |
| 2026-01 | 3.37% | 2.09% | 9.23% |
| 2026-02 | 2.88% | 2.17% | 7.13% |
| 2026-03 | 4.92% | 3.42% | 8.15% |
| 2026-04 | 5.39% | 3.16% | 11.42% |
| 2026-05 | 4.26% | 3.31% | 8.63% |
| 2026-06 | 4.81% | 3.99% | 8.96% |
| 2026-07 | 4.15% | 3.40% | 8.24% |
| 2026-08 | 3.94% | 2.78% | 7.92% |
| 2026-09 | 4.57% | 3.08% | 7.51% |

## MAPE by time of day (UK)

| UK time | GridPulse | NESO day-ahead | Seasonal naive |
| --- | ---: | ---: | ---: |
| 00:00-04:00 | 3.38% | 2.10% | 6.58% |
| 04:00-08:00 | 3.39% | 2.34% | 6.96% |
| 08:00-12:00 | 5.28% | 3.41% | 10.26% |
| 12:00-16:00 | 6.62% | 4.39% | 12.92% |
| 16:00-20:00 | 3.66% | 2.80% | 7.56% |
| 20:00-24:00 | 3.06% | 2.05% | 5.89% |

## What the model leans on (2026-09 model)

Share of the median model's total split gain.

| Feature | Share |
| --- | ---: |
| `demand_lag_7d` | 25.5% |
| `demand_lag_14d` | 13.0% |
| `demand_lag_2d` | 8.6% |
| `lead_hours` | 5.7% |
| `day_of_year_sin` | 5.5% |
| `wind_speed_100m_ms` | 5.2% |
| `demand_mean_24h` | 4.8% |
| `temperature_c` | 4.7% |
| `solar_radiation_wm2` | 4.6% |
| `demand_latest` | 4.5% |
