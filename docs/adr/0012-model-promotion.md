# 0012. Model promotion: champion and challenger compared on data neither has seen

- **Status:** Accepted
- **Date:** 2026-11-02

## Context

The forecast job serves whichever model the MLflow registry marks `champion` (step 2.7). Models are retrained
regularly, on more data and sometimes with new code. A retrain must not be allowed to make production worse, so a
new model ("challenger") has to be compared with the champion before it replaces it.

The comparison has to be fair. The champion was trained on data up to its cutoff C. Scoring it on any period before
C would be grading it on questions it has seen the answers to, and a challenger trained later would have seen even
more. The test months in the backtests are also off limits: re-scoring them on every retrain would tune the system
to them (ADR-0011).

## Options considered

1. **Promote every retrain.** Simple, and fresher data usually helps, but a code bug or bad input data goes straight
   to production.
2. **Compare on a fixed recent holdout.** The champion has usually been trained on part of it, so it wins unfairly,
   or the holdout is excluded from both and the newest data is never used for training.
3. **Compare on a stored score.** The champion's score from its own registration was measured on a different period,
   so the two numbers aren't comparable.
4. **Train the challenger on the champion's data, and score both on everything since C.** Neither has seen it, and
   the only difference between them is the code or configuration.

## Decision

Option 4, plus absolute gates, in `training.py`:

- The challenger, the current code and configuration, is trained on exactly the data the champion had, using one
  shared definition of "trained up to C" (`fit_rows`). Both forecast everything issued since C and are scored on it.
- **Promote** only if the challenger's MAE is no more than 1% worse than the champion's, its MAE is at most 75% of
  seasonal naive's, and its P10-P90 coverage is between 65% and 95%. Unchanged code scores identically, so a routine
  retrain passes and is promoted for its fresher data; a regression doesn't.
- What gets registered and promoted is a model refit on **all** data up to now, not the challenger itself.
- With no champion, or less than a week since its cutoff, only the absolute gates apply, on a held-out last two
  weeks.
- Every version is registered either way, with its training cutoff, git commit, scores and the gate's reasons, so a
  rejected model can be inspected. `train --promote always` overrides the gate (for example after a deliberate
  method change validated in the backtests); `--promote never` registers without promoting.
- A scheduled retrain that fails its gate exits non-zero, so it shows up as a failed job.
- An already-registered version can be promoted past its gate with `promote --task ... --version ... --reason ...`.
  The reason is required and stored on the version with the time and the champion it replaced, so every override
  can be explained later. Lowering a threshold after seeing a result is not an option: that is the same overfitting
  as re-scoring the test months.

## Consequences

- A comparison needs at least a week of unseen data, so a champion can't be replaced by a better method faster than
  that without `--promote always`.
- Retraining costs two fits per task (the challenger, then the final model): about 8 minutes per task on a 2-core
  Codespace. Forecasting no longer trains: it loads the champion and predicts.
- **First use (2026-11-02):** demand v1, the first demand model, passed accuracy (MAE 49% of seasonal naive's) but
  its P10-P90 coverage was 64%, just under the 65% floor, on a two-week holdout that included the clock change and
  half-term. Its 12-month rolling backtest coverage was 78%. Two weeks of strongly autocorrelated half-hours are thin
  evidence, so it was promoted manually with that reason recorded; the floor stays at 65%.
- The gate compares point accuracy and calibration, not every metric. A change that trades a little MAE for much
  better intervals would need `--promote always` and a recorded reason.
