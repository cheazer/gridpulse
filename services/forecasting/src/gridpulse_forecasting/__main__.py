"""Command line, against the running stack:

uv run --env-file ../../.env python -m gridpulse_forecasting check-db
uv run --env-file ../../.env python -m gridpulse_forecasting build-dataset --from 2025-01-01 --to 2025-12-31
"""

from __future__ import annotations

import argparse
import datetime as dt
import sys

from gridpulse_forecasting.config import DatabaseSettings
from gridpulse_forecasting.dataset import build_day_ahead
from gridpulse_forecasting.db import Database
from gridpulse_forecasting.features import FEATURES, LAG_DAYS
from gridpulse_forecasting.sources import load_sources, load_targets

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


def build_dataset(first: dt.date, last: dt.date, output: str | None) -> int:
    """Build day-ahead examples for UK days first..last and report how complete each feature is."""
    db = Database(DatabaseSettings())
    # Inputs reach back as far as the longest lag; a UTC day either side covers the UK-day boundaries.
    start = dt.datetime.combine(first - dt.timedelta(days=max(LAG_DAYS) + 1), dt.time(), dt.UTC)
    end = dt.datetime.combine(last + dt.timedelta(days=2), dt.time(), dt.UTC)
    days = [first + dt.timedelta(days=n) for n in range((last - first).days + 1)]

    examples = build_day_ahead(load_sources(db, start, end), load_targets(db, start, end), days)

    print(
        f"{len(examples):,} examples over {len(days)} days; target known for {examples['target_mw'].notna().mean():.1%}"
    )
    missing = examples[FEATURES].isna().mean().sort_values(ascending=False)
    print("Missing values per feature:")
    print(missing[missing > 0].map("{:.1%}".format).to_string() if (missing > 0).any() else "  none")
    if output:
        examples.to_parquet(output, index=False)
        print(f"Wrote {output}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="gridpulse_forecasting")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check-db", help="connect read-only and summarise the benchmark views")
    build = commands.add_parser("build-dataset", help="build day-ahead examples and report feature completeness")
    build.add_argument("--from", dest="first", type=dt.date.fromisoformat, required=True, help="first UK day")
    build.add_argument("--to", dest="last", type=dt.date.fromisoformat, required=True, help="last UK day")
    build.add_argument("--output", help="write the examples to this Parquet file")
    args = parser.parse_args(argv)
    if args.command == "check-db":
        return check_db()
    if args.command == "build-dataset":
        return build_dataset(args.first, args.last, args.output)
    parser.error(f"unknown command {args.command}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
