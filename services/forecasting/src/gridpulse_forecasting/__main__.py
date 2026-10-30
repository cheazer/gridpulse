"""Command line, against the running stack:

uv run --env-file ../../.env python -m gridpulse_forecasting check-db
uv run --env-file ../../.env python -m gridpulse_forecasting build-dataset --from 2025-01-01 --to 2025-12-31
uv run --env-file ../../.env python -m gridpulse_forecasting backtest --task demand --model seasonal-naive
uv run --env-file ../../.env python -m gridpulse_forecasting backtest --task carbon --model lightgbm-cqr --report r.md

Set MLFLOW_TRACKING_URI (e.g. http://localhost:5000) to record backtests in MLflow.
"""

from __future__ import annotations

import argparse
import datetime as dt
import sys
from pathlib import Path

import pandas as pd

from gridpulse_forecasting.backtest import run_backtest
from gridpulse_forecasting.config import DatabaseSettings
from gridpulse_forecasting.dataset import build_day_ahead
from gridpulse_forecasting.db import Database
from gridpulse_forecasting.experiments import VALIDATION_LAST_DAY, VALIDATION_MONTHS, variant_models
from gridpulse_forecasting.features import FEATURES, LAG_DAYS, LONDON
from gridpulse_forecasting.report import accuracy_report
from gridpulse_forecasting.sources import load_sources
from gridpulse_forecasting.tasks import DEMAND, TASKS, Task
from gridpulse_forecasting.tracking import log_backtest

# The first day with two weeks of history behind it, for the 14-day lag.
FIRST_DAY = dt.date(2024, 11, 13)

# The two series every Phase 2 backtest joins on (ADR-0010).
CHECK_QUERY = """
SELECT 'national_demand_actual' AS series, count(*) AS half_hours, min(valid_from) AS first, max(valid_from) AS last
  FROM national_demand_actual
UNION ALL
SELECT 'ndf_day_ahead', count(*), min(valid_from), max(valid_from)
  FROM ndf_day_ahead
"""


def check_db() -> int:
    """Connect as the forecasting login and show what the benchmark views hold."""
    frame = Database(DatabaseSettings()).read_frame(CHECK_QUERY)
    print(frame.to_string(index=False))
    return 0


def input_window(first: dt.date, last: dt.date) -> tuple[dt.datetime, dt.datetime]:
    """UTC range to load for UK days first..last: back as far as the longest lag, a day either side for UK days."""
    start = dt.datetime.combine(first - dt.timedelta(days=max(LAG_DAYS) + 1), dt.time(), dt.UTC)
    end = dt.datetime.combine(last + dt.timedelta(days=2), dt.time(), dt.UTC)
    return start, end


def load_examples(db: Database, task: Task, first: dt.date, last: dt.date) -> pd.DataFrame:
    start, end = input_window(first, last)
    days = [first + dt.timedelta(days=n) for n in range((last - first).days + 1)]
    return build_day_ahead(load_sources(db, start, end), task.load_targets(db, start, end), days)


def build_dataset(task: Task, first: dt.date, last: dt.date, output: str | None) -> int:
    """Build day-ahead examples for UK days first..last and report how complete each feature is."""
    examples = load_examples(Database(DatabaseSettings()), task, first, last)
    days = examples["issue_time"].nunique()
    print(f"{len(examples):,} examples over {days} days; target known for {examples['target'].notna().mean():.1%}")
    missing = examples[FEATURES].isna().mean().sort_values(ascending=False)
    print("Missing values per feature:")
    print(missing[missing > 0].map("{:.1%}".format).to_string() if (missing > 0).any() else "  none")
    if output:
        examples.to_parquet(output, index=False)
        print(f"Wrote {output}")
    return 0


def backtest(
    task: Task, model_name: str, months: int, first: dt.date, last: dt.date, report: str | None, stage: str
) -> int:
    """Rolling-origin backtest of one model against NESO and seasonal naive; logs to MLflow if configured."""
    db = Database(DatabaseSettings())
    examples = load_examples(db, task, first, last)
    benchmark = task.load_benchmark(db, *input_window(first, last))
    new_model = task.models[model_name]

    result = run_backtest(new_model, examples, benchmark, months, task.naive_feature)

    pd.set_option("display.width", 160)
    folds = f"{result.predictions['fold'].iloc[0]} to {result.predictions['fold'].iloc[-1]}"
    print(f"Backtest of {task.name} {model_name} ({task.unit}): {folds}")
    print(result.summary.round(2).to_string())
    print()
    print("MAPE % by month:")
    print(result.by_month.round(2).to_string())
    run_id = log_backtest(result, new_model(), task.experiment, model_name, stage)
    print()
    print(f"MLflow run {run_id}" if run_id else "MLFLOW_TRACKING_URI not set: not logged")
    if report:
        today = pd.Timestamp.now(tz=LONDON).date()
        Path(report).write_text(accuracy_report(result, task, model_name, run_id, today), encoding="utf-8")
        print(f"Wrote {report}")
    return 0


def compare_variants(first: dt.date, only: list[str] | None) -> int:
    """Backtest every experiments.VARIANTS demand model on the validation window, logged as stage=validation."""
    last = dt.date.fromisoformat(VALIDATION_LAST_DAY)
    db = Database(DatabaseSettings())
    examples = load_examples(db, DEMAND, first, last)
    neso = DEMAND.load_benchmark(db, *input_window(first, last))

    rows = {}
    for name, new_model in variant_models().items():
        if only and name not in only:
            continue
        result = run_backtest(new_model, examples, neso, VALIDATION_MONTHS)
        log_backtest(result, new_model(), DEMAND.experiment, name, "validation")
        rows[name] = result.summary.loc["model", ["mape_pct", "mae", "pinball_p10", "pinball_p90"]]
        months = f"{result.predictions['fold'].iloc[0]} to {result.predictions['fold'].iloc[-1]}"
        print(f"{name}: MAPE {rows[name]['mape_pct']:.2f}%", flush=True)
    rows["neso"] = result.summary.loc["neso", ["mape_pct", "mae"]]
    print()
    print(f"Validation window {months}:")
    print(pd.DataFrame(rows).T.round(2).to_string())
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="gridpulse_forecasting")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check-db", help="connect read-only and summarise the benchmark views")
    build = commands.add_parser("build-dataset", help="build day-ahead examples and report feature completeness")
    build.add_argument("--from", dest="first", type=dt.date.fromisoformat, required=True, help="first UK day")
    build.add_argument("--to", dest="last", type=dt.date.fromisoformat, required=True, help="last UK day")
    build.add_argument("--output", help="write the examples to this Parquet file")
    build.add_argument("--task", choices=sorted(TASKS), default="demand", help="which target column to fill")
    test = commands.add_parser("backtest", help="rolling-origin backtest against NESO and seasonal naive")
    test.add_argument("--task", choices=sorted(TASKS), default="demand")
    test.add_argument("--model", required=True, help="a model of the task, e.g. seasonal-naive or lightgbm-cqr")
    test.add_argument("--months", type=int, default=12, help="test months, the most recent complete ones")
    test.add_argument("--from", dest="first", type=dt.date.fromisoformat, default=FIRST_DAY, help="first UK day")
    test.add_argument("--to", dest="last", type=dt.date.fromisoformat, help="last UK day (default: yesterday)")
    test.add_argument("--report", help="write a Markdown accuracy report to this file")
    test.add_argument(
        "--stage",
        choices=["validation", "test"],
        default="test",
        help="validation: choosing between designs (--to before the test months); test: the final score",
    )
    compare = commands.add_parser("compare-variants", help="backtest every experiment variant on the validation months")
    compare.add_argument("--from", dest="first", type=dt.date.fromisoformat, default=FIRST_DAY, help="first UK day")
    compare.add_argument("--only", nargs="+", choices=sorted(variant_models()), help="run just these variants")
    args = parser.parse_args(argv)
    if args.command == "check-db":
        return check_db()
    if args.command == "build-dataset":
        return build_dataset(TASKS[args.task], args.first, args.last, args.output)
    if args.command == "compare-variants":
        return compare_variants(args.first, args.only)
    if args.command == "backtest":
        task = TASKS[args.task]
        if args.model not in task.models:
            parser.error(f"--model for {task.name}: choose from {', '.join(sorted(task.models))}")
        yesterday = pd.Timestamp.now(tz=LONDON).date() - dt.timedelta(days=1)
        return backtest(task, args.model, args.months, args.first, args.last or yesterday, args.report, args.stage)
    parser.error(f"unknown command {args.command}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
