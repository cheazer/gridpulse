# 0016. Model studies: what Phase 5 tried, and what it changed

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

After Phase 2 the demand model halved seasonal naive's error but trailed NESO's day-ahead forecast (4.23% MAPE
against 2.85%), and the rolling model was no better at short leads than at long ones. Phase 5 asked four questions,
each answered by the same rolling-origin backtest as the headline results. The numbers are in
[docs/results/model-studies.md](../results/model-studies.md).

## Findings and decisions

1. **More history helps demand, but more history doesn't exist.**
   - Demand error falls steadily with training history: 5.91% MAPE with 3 months, 4.53% with 18, still improving.
     Carbon levels off at 12 months.
   - The model trains on *archived weather forecasts*, what was forecast at the time. Open-Meteo's archive of them
     starts in spring 2024 (solar radiation in June 2024), and our history starts in October 2024. Older demand data
     exists, but training on it would mean training on actual weather instead, which is leakage: the backtest would
     look better than live forecasting could be.
   - **Decision:** no extra backfill. Accuracy should keep improving as live history accumulates; the weekly retrain
     picks that up without changes.
2. **The short-range input helps only slightly, and only where it should.**
   - With `demand_latest_vs_last_week_pct`, error falls 2.8% at 0–6 hours and about 1% at 6–24 hours, and is
     slightly worse at 36–48. Over all leads, 5.19% → 5.17% MAPE on the validation months.
   - **Decision:** not adopted in production. A gain this small, on three validation months, isn't worth a new
     champion. The feature stays in the dataset for the next round.
3. **NESO + correction beats NESO.**
   - A model that takes NESO's day-ahead forecast as an input and learns its error scores **2.73% MAPE against
     NESO's 2.85%** (MAE 681 against 709 MW), over 12 test months.
   - It is labelled separately, because it answers a different question: not "can GridPulse forecast demand on its
     own?" (4.24%), but "can NESO's own forecast be improved?". It needs NESO's forecast at run time.
   - **Decision:** reported, not served. Serving it would need NDF in the forecast job's inputs; that's the next step
     if the scheduler ever uses a demand forecast.
4. **Gradient-boosted trees beat a neural network on these features.**
   - A two-layer MLP on the same features and folds: demand 4.77% against LightGBM's 4.24%, carbon 26.6% against
     22.0% MAPE.
   - That's the usual result for tabular data of this size (~35,000 day-ahead rows): trees handle mixed scales, missing
     values and thresholds (heating below 15.5 °C) without help.
   - **Decision:** LightGBM stays. A sequence model such as N-HiTS, learning from the raw half-hourly history rather
     than these features, is the remaining deep-learning comparison; it was left out to keep Phase 5 short.

## Consequences

- No change to the served models. The headline results stand, and the README reports the studies beside them.
- `python -m gridpulse_forecasting studies` reruns all four. The studies compare P50s from a median-only LightGBM, so
  they take minutes rather than hours. scikit-learn is an `experiments` dependency group, in neither image.
