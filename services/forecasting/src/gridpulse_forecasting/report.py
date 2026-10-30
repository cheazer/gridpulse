"""Render a backtest as Markdown for docs/results (ADR-0011). Plain tables, so it reads well on GitHub."""

from __future__ import annotations

import datetime as dt
from collections.abc import Sequence

import pandas as pd

from gridpulse_forecasting.backtest import MODEL, NAIVE, NESO, BacktestResult
from gridpulse_forecasting.features import LONDON

LABELS = {MODEL: "GridPulse", NESO: "NESO day-ahead", NAIVE: "Seasonal naive"}


def accuracy_report(result: BacktestResult, model_name: str, run_id: str | None, generated: dt.date) -> str:
    p = result.predictions
    summary = result.summary
    half_hours = len(p.dropna(subset=["actual_mw", f"{MODEL}_p50", NESO, NAIVE]))
    folds = sorted(p["fold"].unique())
    lines = [
        "# Day-ahead demand forecast accuracy",
        "",
        f"Generated {generated:%Y-%m-%d} by "
        f"`python -m gridpulse_forecasting backtest --model {model_name} --report ...`"
        + (f" (MLflow run `{run_id}`)." if run_id else "."),
        "",
        f"Rolling-origin backtest over **{folds[0]} to {folds[-1]}** ({len(folds)} months): a fresh model each month, "
        "fitted on what was known at the month's first issue time, forecasting each day as of **13:00 UK time the "
        "day before**, the same cut-off as NESO's day-ahead forecast (ADR-0010, ADR-0011). All forecasts are scored "
        f"on the same **{half_hours:,} half-hours**; actuals are NESO's settled national "
        "demand (ND), or Elexon's initial outturn where ND isn't published yet.",
        "",
        "## Headline",
        "",
        table(
            ["Forecast", "MAPE", "MAE", "RMSE", "Bias"],
            [
                [
                    LABELS[str(name)],
                    f"{row['mape_pct']:.2f}%",
                    f"{row['mae_mw']:,.0f} MW",
                    f"{row['rmse_mw']:,.0f} MW",
                    f"{row['bias_mw']:+,.0f} MW",
                ]
                for name, row in summary.iterrows()
            ],
        ),
        "",
        "Bias is the mean of forecast minus actual: negative means forecasts were too low on average.",
    ]

    if "coverage_p10_p90" in summary.columns:
        model = summary.loc[MODEL]
        lines += [
            "",
            "## Prediction intervals",
            "",
            table(
                ["P10-P90 coverage (target 80%)", "Mean width", "Pinball P10", "Pinball P50", "Pinball P90"],
                [
                    [
                        f"{model['coverage_p10_p90']:.1%}",
                        f"{(p[f'{MODEL}_p90'] - p[f'{MODEL}_p10']).mean():,.0f} MW",
                        f"{model['pinball_p10_mw']:,.0f} MW",
                        f"{model['pinball_p50_mw']:,.0f} MW",
                        f"{model['pinball_p90_mw']:,.0f} MW",
                    ]
                ],
            ),
        ]

    by_month = result.by_month
    lines += [
        "",
        "## MAPE by month",
        "",
        table(
            ["Month", *(LABELS[c] for c in by_month.columns)],
            [[month, *(f"{v:.2f}%" for v in row)] for month, row in by_month.iterrows()],
        ),
        "",
        "## MAPE by time of day (UK)",
        "",
        by_time_of_day(p),
    ]

    importance = getattr(result.final_model, "feature_importance", None)
    if importance is not None:
        top = importance().head(10)
        lines += [
            "",
            f"## What the model leans on ({folds[-1]} model)",
            "",
            "Share of the median model's total split gain.",
            "",
            table(["Feature", "Share"], [[f"`{name}`", f"{share:.1%}"] for name, share in top.items()]),
        ]
    return "\n".join(lines) + "\n"


def by_time_of_day(p: pd.DataFrame) -> str:
    common = p.dropna(subset=["actual_mw", f"{MODEL}_p50", NESO, NAIVE])
    hour = common["target_time"].dt.tz_convert(LONDON).dt.hour
    block = (hour // 4 * 4).map(lambda h: f"{h:02d}:00-{h + 4:02d}:00")
    columns = {MODEL: f"{MODEL}_p50", NESO: NESO, NAIVE: NAIVE}
    ape = pd.DataFrame(
        {name: (common[col] - common["actual_mw"]).abs() / common["actual_mw"] * 100 for name, col in columns.items()}
    )
    mape = ape.groupby(block).mean()
    return table(
        ["UK time", *(LABELS[c] for c in mape.columns)],
        [[str(block_name), *(f"{v:.2f}%" for v in row)] for block_name, row in mape.iterrows()],
    )


def table(headers: Sequence[str], rows: Sequence[Sequence[object]]) -> str:
    align = ["---", *["---:"] * (len(headers) - 1)]
    out = [f"| {' | '.join(headers)} |", f"| {' | '.join(align)} |"]
    out += [f"| {' | '.join(str(cell) for cell in row)} |" for row in rows]
    return "\n".join(out)
