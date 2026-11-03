"""Command line, against the running stack:

uv run --env-file ../../.env python -m gridpulse_forecasting check-db
uv run --env-file ../../.env python -m gridpulse_forecasting build-dataset --from 2025-01-01 --to 2025-12-31
uv run --env-file ../../.env python -m gridpulse_forecasting backtest --task demand --model seasonal-naive
uv run --env-file ../../.env python -m gridpulse_forecasting backtest --task carbon --model lightgbm-cqr --report r.md
uv run --env-file ../../.env python -m gridpulse_forecasting train --task all
uv run --env-file ../../.env python -m gridpulse_forecasting forecast --task all

Set MLFLOW_TRACKING_URI (e.g. http://localhost:5000) to record backtests in MLflow.
"""

from __future__ import annotations

import argparse
import datetime as dt
import logging
import os
import sys
from pathlib import Path

import pandas as pd
from opentelemetry import trace

from gridpulse_forecasting import job, telemetry
from gridpulse_forecasting.backtest import run_backtest
from gridpulse_forecasting.config import DatabaseSettings, JobDatabaseSettings
from gridpulse_forecasting.dataset import build_day_ahead, build_rolling, rolling_issue_times
from gridpulse_forecasting.db import Database
from gridpulse_forecasting.experiments import VALIDATION_LAST_DAY, VALIDATION_MONTHS, variant_models
from gridpulse_forecasting.features import FEATURES, LAG_DAYS, LONDON
from gridpulse_forecasting.report import accuracy_report
from gridpulse_forecasting.sources import load_sources
from gridpulse_forecasting.tasks import DEMAND, TASKS, Task
from gridpulse_forecasting.tracking import git_commit, log_backtest

# The forecast job logs (JSON with trace IDs, as a scheduled job); the other commands print reports for a person.
log = logging.getLogger("gridpulse_forecasting.forecast")

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


def load_examples(db: Database, task: Task, first: dt.date, last: dt.date, mode: str = "day-ahead") -> pd.DataFrame:
    """Day-ahead examples (issued 13:00 UK the day before) or rolling ones (every 6 hours, 48 hours ahead)."""
    start, end = input_window(first, last)
    sources, targets = load_sources(db, start, end), task.load_targets(db, start, end)
    if mode == "rolling":
        return build_rolling(sources, targets, rolling_issue_times(first, last))
    days = [first + dt.timedelta(days=n) for n in range((last - first).days + 1)]
    return build_day_ahead(sources, targets, days)


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
    task: Task,
    model_name: str,
    months: int,
    first: dt.date,
    last: dt.date,
    report: str | None,
    stage: str,
    mode: str,
) -> int:
    """Rolling-origin backtest of one model against NESO and seasonal naive; logs to MLflow if configured.

    ``mode`` "rolling" backtests what the forecast job serves: the production model, every lead from 0 to 48 hours.
    """
    db = Database(DatabaseSettings())
    examples = load_examples(db, task, first, last, mode)
    benchmark = task.load_benchmark(db, *input_window(first, last))
    new_model = task.production if mode == "rolling" and model_name == "lightgbm-cqr" else task.models[model_name]

    result = run_backtest(new_model, examples, benchmark, months, task.naive_feature)

    pd.set_option("display.width", 160)
    folds = f"{result.predictions['fold'].iloc[0]} to {result.predictions['fold'].iloc[-1]}"
    print(f"Backtest of {task.name} {model_name} ({task.unit}): {folds}")
    print(result.summary.round(2).to_string())
    print()
    print("MAPE % by month:")
    print(result.by_month.round(2).to_string())
    run_name = f"{model_name}-rolling" if mode == "rolling" else model_name
    run_id = log_backtest(result, new_model(), task.experiment, run_name, stage)
    print()
    print(f"MLflow run {run_id}" if run_id else "MLFLOW_TRACKING_URI not set: not logged")
    if report:
        today = pd.Timestamp.now(tz=LONDON).date()
        text = accuracy_report(result, task, model_name, run_id, today, rolling=mode == "rolling")
        Path(report).write_text(text, encoding="utf-8")
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


def run_forecast(task_names: list[str], issue: str | None) -> int:
    """Forecast the next 48 hours with each task's champion and store it, as the forecast job's own login (V7)."""
    from gridpulse_forecasting.registry import load_champion  # MLflow only where the registry is used

    # A trace per run, a span per task inside it: how long each step took, what it stored, and the error if one failed.
    # No metrics: a job that never runs sends none, so the API's gridpulse.forecast.age is what alerts on (step 4.6).
    tracer = trace.get_tracer(telemetry.SCOPE)
    db = Database(JobDatabaseSettings(), read_only=False)
    issue_time = pd.Timestamp(issue).tz_convert("UTC") if issue else job.issue_time_now()
    with tracer.start_as_current_span("forecast job", attributes={"gridpulse.issued_at": issue_time.isoformat()}):
        for name in task_names:
            with tracer.start_as_current_span(f"forecast {name}", attributes={"gridpulse.task": name}) as span:
                champion = load_champion(name)
                if champion is None:
                    log.error("%s: no champion in the model registry; run `train --task %s` first", name, name)
                    span.set_status(trace.StatusCode.ERROR, "no champion")
                    return 1
                rows = job.forecast(db, TASKS[name], issue_time, champion)
                stored = job.store(db, rows)
                span.set_attributes({"gridpulse.model_version": champion.label, "gridpulse.half_hours": stored})
            p50 = rows["p50"]
            log.info(
                "%s: stored %d half-hours issued %s with %s, P50 %s to %s %s",
                name,
                stored,
                f"{issue_time:%Y-%m-%d %H:%M}Z",
                champion.label,
                f"{p50.min():,.0f}",
                f"{p50.max():,.0f}",
                TASKS[name].unit,
            )
    return 0


def run_train(task_names: list[str], first: dt.date, promote: str) -> int:
    """Retrain each task, gate the result against the champion (training.py), register it, maybe promote it."""
    import mlflow

    from gridpulse_forecasting import registry, training

    if not os.environ.get("MLFLOW_TRACKING_URI"):
        print("train needs MLFLOW_TRACKING_URI: models are registered in MLflow", file=sys.stderr)
        return 2
    db = Database(DatabaseSettings())
    now = job.issue_time_now()
    status = 0
    for name in task_names:
        task = TASKS[name]
        # Reach MLflow before spending minutes training: a misconfigured server should fail in seconds.
        mlflow.set_experiment(task.experiment)
        _, examples = training.load_rolling(db, task, now, first)
        current = registry.load_champion(name)
        outcome = training.challenge(task, examples, now, (current.model, current.training_cutoff) if current else None)
        promote_it = promote == "always" or (promote == "auto" and outcome.decision.promote)

        with mlflow.start_run(run_name=f"train {name} {now:%Y-%m-%d %H:%M}Z"):
            c = outcome.challenger
            mlflow.set_tags({"stage": "training", "git_commit": git_commit(), "promoted": str(promote_it)})
            mlflow.log_params({**outcome.model.params(), "evaluated_since": outcome.evaluated_since.isoformat()})
            mlflow.log_metrics(
                {
                    "challenger_mae": c.mae,
                    "seasonal_naive_mae": c.naive_mae,
                    "challenger_coverage_p10_p90": c.coverage,
                    "evaluation_forecasts": c.forecasts,
                    **({"champion_mae": outcome.champion_mae} if outcome.champion_mae is not None else {}),
                }
            )
            mlflow.log_text("\n".join(outcome.decision.reasons), "decision.txt")
            version = registry.register(
                name,
                outcome.model,
                now,
                {
                    "git_commit": git_commit(),
                    "decision": "promoted" if promote_it else "not promoted",
                    "challenger_mae": f"{c.mae:.3f}",
                },
                promote=promote_it,
            )

        verdict = "PROMOTED to champion" if promote_it else "registered, NOT promoted"
        print(f"{name}: version {version} {verdict} (evaluated on {c.forecasts:,} unseen forecasts)", flush=True)
        for reason in outcome.decision.reasons:
            print(f"  {reason}", flush=True)
        if promote == "auto" and not outcome.decision.promote:
            status = 1  # a scheduled retrain that fails its gate should show up as a failed job
    return status


def run_promote(task_name: str, version: str, reason: str) -> int:
    """Override the gate for one registered version, with the reason recorded in the registry (ADR-0012)."""
    from gridpulse_forecasting import registry

    if not os.environ.get("MLFLOW_TRACKING_URI"):
        print("promote needs MLFLOW_TRACKING_URI", file=sys.stderr)
        return 2
    replaced = registry.promote_manually(task_name, version, reason, pd.Timestamp.now(tz="UTC"))
    print(
        f"{registry.model_name(task_name)}: v{version} is champion "
        f"(replacing {'v' + replaced if replaced else 'none'}), reason recorded: {reason}"
    )
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
    test.add_argument(
        "--mode",
        choices=["day-ahead", "rolling"],
        default="day-ahead",
        help="day-ahead: issued 13:00 UK the day before; rolling: issued every 6 hours, 48 hours ahead",
    )
    compare = commands.add_parser("compare-variants", help="backtest every experiment variant on the validation months")
    compare.add_argument("--from", dest="first", type=dt.date.fromisoformat, default=FIRST_DAY, help="first UK day")
    compare.add_argument("--only", nargs="+", choices=sorted(variant_models()), help="run just these variants")
    fc = commands.add_parser("forecast", help="forecast the next 48 hours with the champion and store it")
    fc.add_argument("--task", choices=[*sorted(TASKS), "all"], default="all")
    fc.add_argument("--issue-time", help="ISO timestamp with zone (default: the latest half-hour)")
    tr = commands.add_parser("train", help="retrain, gate against the champion, register in MLflow")
    tr.add_argument("--task", choices=[*sorted(TASKS), "all"], default="all")
    tr.add_argument("--from", dest="first", type=dt.date.fromisoformat, default=FIRST_DAY, help="first training day")
    tr.add_argument(
        "--promote",
        choices=["auto", "always", "never"],
        default="auto",
        help="auto: only if the gate passes; always: override the gate (say why in the commit); never: register only",
    )
    pr = commands.add_parser("promote", help="make a registered version champion despite its gate, with a reason")
    pr.add_argument("--task", choices=sorted(TASKS), required=True)
    pr.add_argument("--version", required=True, help="the registry version, e.g. 1")
    pr.add_argument("--reason", required=True, help="why the gate is being overridden; stored on the version")
    args = parser.parse_args(argv)
    if args.command == "check-db":
        return check_db()
    if args.command == "build-dataset":
        return build_dataset(TASKS[args.task], args.first, args.last, args.output)
    if args.command == "forecast":
        telemetry.configure("forecast-job")
        telemetry.configure_logging("forecast-job")
        try:
            return run_forecast(sorted(TASKS) if args.task == "all" else [args.task], args.issue_time)
        finally:
            telemetry.flush()  # the process exits next: send the run's spans first
    if args.command == "promote":
        return run_promote(args.task, args.version, args.reason)
    if args.command == "train":
        return run_train(sorted(TASKS) if args.task == "all" else [args.task], args.first, args.promote)
    if args.command == "compare-variants":
        return compare_variants(args.first, args.only)
    if args.command == "backtest":
        task = TASKS[args.task]
        if args.model not in task.models:
            parser.error(f"--model for {task.name}: choose from {', '.join(sorted(task.models))}")
        yesterday = pd.Timestamp.now(tz=LONDON).date() - dt.timedelta(days=1)
        last = args.last or yesterday
        return backtest(task, args.model, args.months, args.first, last, args.report, args.stage, args.mode)
    parser.error(f"unknown command {args.command}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
