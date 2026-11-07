# Model studies (Phase 5)

Generated 2026-11-07 by `python -m gridpulse_forecasting studies` ([studies.py](../../services/forecasting/src/gridpulse_forecasting/studies.py)). Every number is a point forecast (P50) from a model refitted month by month, exactly as the headline backtests do, so they compare with each other; the design decisions they led to are in [ADR-0016](../adr/0016-model-studies.md).

## 1. Learning curve: accuracy against months of training history

The same day-ahead model and test months, refitted each month on only the most recent 3 to 18 months of history (18 is the most every test month has behind it).

### Demand (2026-04 to 2026-09, 6 months)

| Forecast | MAE | MAPE |
| --- | ---: | ---: |
| 3 months | 1,295 MW | 5.91% |
| 6 months | 1,186 MW | 5.44% |
| 9 months | 1,138 MW | 5.19% |
| 12 months | 1,014 MW | 4.70% |
| 18 months | 973 MW | 4.53% |
| NESO | 719 MW | 3.28% |

Scored on the 8,784 half-hours every forecast covers.

### Carbon (2026-04 to 2026-09, 6 months)

| Forecast | MAE | MAPE |
| --- | ---: | ---: |
| 3 months | 27.7 gCO2/kWh | 27.00% |
| 6 months | 25.5 gCO2/kWh | 24.78% |
| 9 months | 25.1 gCO2/kWh | 24.57% |
| 12 months | 23.4 gCO2/kWh | 23.22% |
| 18 months | 23.4 gCO2/kWh | 23.16% |
| NESO | 10.7 gCO2/kWh | 10.39% |

Scored on the 8,783 half-hours every forecast covers.

## 2. Short range: the latest demand against a week ago

The rolling model (every lead from 0 to 48 hours), with and without `demand_latest_vs_last_week_pct`, on the validation months (2025-07 to 2025-09, 3 months): this chooses a design, so not the test months.

| Lead time | MAE without | MAE with short-range input |
| --- | ---: | ---: |
| 0-6 h | 1,186 MW | 1,153 MW |
| 6-12 h | 1,190 MW | 1,177 MW |
| 12-24 h | 1,187 MW | 1,175 MW |
| 24-36 h | 1,176 MW | 1,173 MW |
| 36-48 h | 1,169 MW | 1,178 MW |

Over all leads:

| Forecast | MAE | MAPE |
| --- | ---: | ---: |
| without | 1,180 MW | 5.19% |
| with short-range input | 1,173 MW | 5.17% |
| NESO | 609 MW | 2.72% |

Scored on the 35,328 half-hours every forecast covers.

## 3. NESO + correction

A separately labelled model that takes NESO's day-ahead forecast (as published by 13:00 the day before) as an input and learns its error, against GridPulse's own model, which never sees NESO's forecast. Day-ahead demand, test months 2025-10 to 2026-09, 12 months.

| Forecast | MAE | MAPE |
| --- | ---: | ---: |
| GridPulse | 1,063 MW | 4.24% |
| NESO + correction | 681 MW | 2.73% |
| NESO | 709 MW | 2.85% |

Scored on the 17,520 half-hours every forecast covers.

## 4. Neural network against gradient-boosted trees

A two-layer neural network (128 and 64 units, scikit-learn's MLP with early stopping) on exactly the same features, examples and monthly refits as LightGBM.

### Demand (2025-10 to 2026-09, 12 months)

| Forecast | MAE | MAPE |
| --- | ---: | ---: |
| LightGBM | 1,063 MW | 4.24% |
| Neural network | 1,192 MW | 4.77% |
| NESO | 709 MW | 2.85% |

Scored on the 17,520 half-hours every forecast covers.

### Carbon (2025-10 to 2026-09, 12 months)

| Forecast | MAE | MAPE |
| --- | ---: | ---: |
| LightGBM | 23.3 gCO2/kWh | 21.98% |
| Neural network | 27.3 gCO2/kWh | 26.59% |
| NESO | 9.9 gCO2/kWh | 9.44% |

Scored on the 17,519 half-hours every forecast covers.
