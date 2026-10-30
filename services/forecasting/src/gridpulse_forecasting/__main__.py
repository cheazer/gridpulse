"""Command line:  uv run --env-file ../../.env python -m gridpulse_forecasting check-db"""

from __future__ import annotations

import argparse
import sys

from gridpulse_forecasting.config import DatabaseSettings
from gridpulse_forecasting.db import Database

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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="gridpulse_forecasting")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check-db", help="connect read-only and summarise the benchmark views")
    args = parser.parse_args(argv)
    if args.command == "check-db":
        return check_db()
    parser.error(f"unknown command {args.command}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
