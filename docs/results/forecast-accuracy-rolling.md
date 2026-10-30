# Rolling 48-hour demand forecast accuracy

Generated 2026-10-30 by `python -m gridpulse_forecasting backtest --task demand --model lightgbm-cqr --mode rolling --report ...` (MLflow run `58ae8882453a4a779ed0c364ffe1b5bd`).

Rolling-origin backtest over **2025-10 to 2026-09** (12 months): a fresh model each month, fitted on what was known at the month's first issue time, issuing forecasts **every 6 hours (01:00, 07:00, 13:00, 19:00 UK) for the next 48 hours**, as the forecast job does. Each half-hour is forecast several times, at different lead times. (ADR-0010, ADR-0011.) All forecasts are scored on the same **140,160 forecasts**. Actuals are NESO's settled national demand (ND), or Elexon's initial outturn where ND isn't published yet. NESO's forecast is its day-ahead NDF as published by 13:00 UK time the day before: the same information cut-off as ours (ADR-0010).

## Headline

| Forecast | MAPE | MAE | RMSE | Bias |
| --- | ---: | ---: | ---: | ---: |
| GridPulse | 4.37% | 1,089 MW | 1,442 MW | -95 MW |
| NESO day-ahead | 2.85% | 709 MW | 958 MW | -85 MW |
| Seasonal naive | 8.36% | 2,119 MW | 2,881 MW | -16 MW |

Bias is the mean of forecast minus actual: negative means forecasts were too low on average.

## Prediction intervals

| P10-P90 coverage (target 80%) | Mean width | Pinball P10 | Pinball P50 | Pinball P90 |
| --- | ---: | ---: | ---: | ---: |
| 78.1% | 3,662 MW | 275 MW | 545 MW | 271 MW |

## MAPE by month

| Month | GridPulse | NESO day-ahead | Seasonal naive |
| --- | ---: | ---: | ---: |
| 2025-10 | 3.97% | 2.63% | 7.63% |
| 2025-11 | 4.60% | 2.09% | 7.99% |
| 2025-12 | 4.45% | 2.01% | 7.49% |
| 2026-01 | 3.34% | 2.09% | 9.23% |
| 2026-02 | 3.06% | 2.17% | 7.13% |
| 2026-03 | 5.23% | 3.43% | 8.15% |
| 2026-04 | 5.35% | 3.16% | 11.42% |
| 2026-05 | 4.29% | 3.31% | 8.63% |
| 2026-06 | 4.92% | 3.99% | 8.96% |
| 2026-07 | 4.47% | 3.40% | 8.24% |
| 2026-08 | 4.18% | 2.78% | 7.92% |
| 2026-09 | 4.56% | 3.08% | 7.51% |

## MAPE by time of day (UK)

| UK time | GridPulse | NESO day-ahead | Seasonal naive |
| --- | ---: | ---: | ---: |
| 00:00-04:00 | 3.64% | 2.10% | 6.58% |
| 04:00-08:00 | 3.61% | 2.34% | 6.96% |
| 08:00-12:00 | 5.43% | 3.41% | 10.26% |
| 12:00-16:00 | 6.86% | 4.39% | 12.92% |
| 16:00-20:00 | 3.70% | 2.80% | 7.56% |
| 20:00-24:00 | 3.00% | 2.05% | 5.89% |

## MAPE by lead time

How far ahead the forecast was issued. NESO's forecast is the same whatever the lead (its 13:00 day-ahead or final forecast), so only GridPulse's column should change down the table.

| Lead | GridPulse | NESO day-ahead | Seasonal naive |
| --- | ---: | ---: | ---: |
| 0-6 h | 4.35% | 2.85% | 8.36% |
| 6-12 h | 4.37% | 2.85% | 8.36% |
| 12-24 h | 4.38% | 2.85% | 8.36% |
| 24-36 h | 4.36% | 2.85% | 8.36% |
| 36-48 h | 4.40% | 2.85% | 8.36% |

## What the model leans on (2026-09 model)

Share of the median model's total split gain.

| Feature | Share |
| --- | ---: |
| `demand_lag_7d` | 28.4% |
| `demand_lag_14d` | 14.7% |
| `half_hour_of_day` | 7.9% |
| `demand_lag_2d` | 7.6% |
| `day_of_year_sin` | 6.8% |
| `day_of_year_cos` | 6.0% |
| `solar_radiation_wm2` | 5.2% |
| `wind_speed_100m_ms` | 5.0% |
| `day_of_week` | 4.6% |
| `temperature_c` | 4.6% |
