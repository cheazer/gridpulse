# 0010. The day-ahead benchmark: NESO's NDF as published by 13:00 the day before

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

Phase 2's headline claim is "our day-ahead demand forecast beats NESO's". That needs a precise, fair definition
of NESO's day-ahead forecast. NESO republishes NDF about every 30 minutes (ADR-0007), so every target half-hour has
dozens of revisions, the later ones closer to the event and more accurate.

Two years of revisions (measured 2026-10-29) show *when* a revision covers all of the next day:

| Published (UK time, on D-1) | Covers all of day D |
|---|---|
| 00:00–07:59, 09:00–10:59 | 0% (they stop at 04:30 on D) |
| 08:xx, 11:xx | ~50% |
| **12:xx onwards** | **99.3–100%** |

## Decision

- **NESO's day-ahead forecast** for a half-hour on settlement day D is the **latest NDF revision published at or
  before 13:00 UK time on D-1** that includes that half-hour. In practice that's the ~12:45 publication. The
  database view `grid.ndf_day_ahead` (migration V6) implements this, so the report and Phase 2 can't drift apart.
- **GridPulse's model gets the same information cut-off:** a day-ahead forecast for day D may only use data
  available by 13:00 UK time on D-1, including weather forecasts issued by then (ADR-0008). Comparing a model that
  saw later data against NESO's 13:00 forecast would be unfair in our favour.
- **The target is National Demand (ND),** what NDF forecasts. NESO's settled ND is used where published (it lags
  by a few weeks); otherwise Elexon's initial outturn INDO. The view `grid.national_demand_actual` picks per
  half-hour and says which it used.
- **Metrics:** MAE, RMSE and MAPE, plus bias (mean of forecast minus actual), over all half-hours where both
  exist. A **seasonal-naive** forecast (the same half-hour one week earlier) is reported alongside, as the floor
  any model must clear.

## Consequences

- One number to beat, computed one way, in the data-quality report (`docs/results/data-quality.md`) and later in
  Phase 2's backtests.
- NESO's later revisions are more accurate than its 13:00 one. Any claim of beating NESO must state the 13:00
  cut-off and hold our model to it; comparing against a later revision, or letting our model see later data,
  would misstate the result.
- Changing the cut-off later means changing the view and re-running both the report and the backtests together.
