# Demand model experiments (step 2.4)

The first LightGBM model scored 4.23% MAPE on the test months (Oct 2025 to Sep 2026), against NESO's 2.85%
([forecast-accuracy.md](forecast-accuracy.md)). This is the record of trying to close that gap without fooling
ourselves.

## Protocol

- **Choose on validation, score on test once.** Every variant was backtested on **Jul to Sep 2025**, the three
  months before the test window, with the same rolling-origin method: a monthly refit on what was known, forecasts
  as of 13:00 the day before. The test months were scored once, with the first model, before the round started.
  Re-scoring them after every idea would have tuned the model to them and overstated its accuracy.
- **One change per variant**, so a difference can be attributed to it.
- **Every run is in MLflow** (experiment `day-ahead-demand`, tag `stage=validation`). Reproduce with
  `python -m gridpulse_forecasting compare-variants`; the variants are in
  [experiments.py](../../services/forecasting/src/gridpulse_forecasting/experiments.py).
- Validation folds train on less history than test folds (the July 2025 model never saw a summer), so validation
  errors run higher than test errors. Only the comparison between variants matters here.

## Results (P50 MAPE on Jul to Sep 2025; NESO 2.72%)

| Variant | What changed | MAPE | MAE |
|---|---|---:|---:|
| `v1` | the first model | **5.18%** | 1,169 MW |
| `regional-solar` | + radiation at each of the 8 locations | 5.18% | 1,173 MW |
| `ratio` | predicts demand / demand a week earlier | 5.59% | 1,264 MW |
| `regional-solar-ratio` | both of the above | 5.55% | 1,256 MW |
| `leaves-63` | larger trees | 5.11% | 1,158 MW |
| `leaves-15` | smaller trees | 5.22% | 1,185 MW |
| `lr-0.06-500` | faster learning, fewer rounds | 5.24% | 1,186 MW |
| `min-leaf-30` | smaller minimum leaf | 5.26% | 1,188 MW |
| `week-on-week` | + change in demand and weather since last week | 5.17% | 1,167 MW |
| `week-on-week-regional-solar` | + both feature sets | 5.25% | 1,184 MW |

## Conclusion

**Nothing beats `v1` by more than noise.** The best variant (`leaves-63`, 5.11%) is 0.07 points better on three
months: too small to trust, and choosing it would be choosing noise. The production model stays `v1`, the simplest.

What this rules out, and so what the gap to NESO is likely *not*:

- **Not where the sun shines.** Per-location radiation adds nothing over the national mean.
- **Not trend handling.** Predicting the ratio to last week is clearly worse: it turns errors in last week's
  demand into errors in this week's forecast.
- **Not hyperparameters.** Four reasonable settings span 5.11-5.26%.
- **Not week-on-week context.** The model already infers it from the lags and the weather.

What remains, and goes to Phase 5 ("better models"):

- **History.** Each model trains on one to two years, so it has seen at most one or two of each season. NESO
  publishes historic demand back to 2009; how far back Open-Meteo's archived forecasts reach needs checking. A
  learning curve (accuracy against years of training data) would show whether more history is the lever.
- **NESO's information advantage.** NESO forecasts embedded solar and wind from its own models and many more
  weather sites. A "NESO + correction" model, which uses NDF as an input and learns its errors, would be reported
  separately from the headline model (ADR-0011).
