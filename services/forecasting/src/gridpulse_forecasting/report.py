"""Render a backtest as Markdown for docs/results (ADR-0011). Plain tables, so it reads well on GitHub."""

from __future__ import annotations

import datetime as dt
from collections.abc import Callable, Sequence

import pandas as pd

from gridpulse_forecasting.backtest import MODEL, NAIVE, NESO, BacktestResult
from gridpulse_forecasting.features import LONDON
from gridpulse_forecasting.tasks import Task

POINT = {MODEL: f"{MODEL}_p50", NESO: NESO, NAIVE: NAIVE}


def accuracy_report(result: BacktestResult, task: Task, model_name: str, run_id: str | None, generated: dt.date) -> str:
    p = result.predictions
    summary = result.summary
    common = p.dropna(subset=["actual", *POINT.values()])
    folds = sorted(p["fold"].unique())
    labels = {MODEL: "GridPulse", NESO: task.benchmark_label, NAIVE: "Seasonal naive"}
    # A whole number of MW reads well; carbon intensity in the tens of g/kWh needs a decimal.
    amount = "{:,.0f} " + task.unit if task.unit == "MW" else "{:,.1f} " + task.unit
    signed = amount.replace("{:,", "{:+,")
    # MAPE leads for demand, which never nears zero; MAE leads for carbon intensity, which can.
    by_mae = task.name == "carbon"

    lines = [
        f"# {task.title}",
        "",
        f"Generated {generated:%Y-%m-%d} by "
        f"`python -m gridpulse_forecasting backtest --task {task.name} --model {model_name} --report ...`"
        + (f" (MLflow run `{run_id}`)." if run_id else "."),
        "",
        f"Rolling-origin backtest over **{folds[0]} to {folds[-1]}** ({len(folds)} months): a fresh model each month, "
        "fitted on what was known at the month's first issue time, forecasting each day as of **13:00 UK time the "
        "day before** (ADR-0010, ADR-0011). All forecasts are scored on the same "
        f"**{len(common):,} half-hours**. {task.about}",
        "",
        "## Headline",
        "",
        table(
            ["Forecast", "MAE", "MAPE", "RMSE", "Bias"] if by_mae else ["Forecast", "MAPE", "MAE", "RMSE", "Bias"],
            [
                [
                    labels[str(name)],
                    *(
                        [amount.format(row["mae"]), f"{row['mape_pct']:.2f}%"]
                        if by_mae
                        else [f"{row['mape_pct']:.2f}%", amount.format(row["mae"])]
                    ),
                    amount.format(row["rmse"]),
                    signed.format(row["bias"]),
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
                        amount.format((p[f"{MODEL}_p90"] - p[f"{MODEL}_p10"]).mean()),
                        amount.format(model["pinball_p10"]),
                        amount.format(model["pinball_p50"]),
                        amount.format(model["pinball_p90"]),
                    ]
                ],
            ),
        ]

    metric = "MAE" if by_mae else "MAPE"
    cell = amount.format if by_mae else "{:.2f}%".format
    month = common["fold"]
    hour = common["target_time"].dt.tz_convert(LONDON).dt.hour
    block = (hour // 4 * 4).map(lambda h: f"{h:02d}:00-{h + 4:02d}:00")
    lines += [
        "",
        f"## {metric} by month",
        "",
        grouped(common, month, "Month", labels, by_mae, cell),
        "",
        f"## {metric} by time of day (UK)",
        "",
        grouped(common, block, "UK time", labels, by_mae, cell),
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


def grouped(
    common: pd.DataFrame,
    key: pd.Series,
    heading: str,
    labels: dict[str, str],
    by_mae: bool,
    cell: Callable[[float], str],
) -> str:
    """MAE or MAPE of every forecast per group (month, time of day), on the common half-hours."""
    errors = pd.DataFrame(
        {
            name: (common[column] - common["actual"]).abs() / (1 if by_mae else common["actual"] / 100)
            for name, column in POINT.items()
        }
    )
    means = errors.groupby(key).mean()
    return table(
        [heading, *(labels[c] for c in means.columns)],
        [[str(group), *(cell(v) for v in row)] for group, row in means.iterrows()],
    )


def table(headers: Sequence[str], rows: Sequence[Sequence[object]]) -> str:
    align = ["---", *["---:"] * (len(headers) - 1)]
    out = [f"| {' | '.join(headers)} |", f"| {' | '.join(align)} |"]
    out += [f"| {' | '.join(str(cell) for cell in row)} |" for row in rows]
    return "\n".join(out)
