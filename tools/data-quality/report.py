# /// script
# requires-python = ">=3.11"
# dependencies = ["psycopg[binary]>=3.2"]
# ///
"""Data-quality report for the GridPulse database (step 1.9).

Checks what Phase 2 relies on: coverage and gaps per series, duplicates, clock-change days, value ranges,
agreement between sources, and the day-ahead benchmark NESO sets (ADR-0010). Writes Markdown.

Run from the repo root against a running stack (reads .env):
    uv run tools/data-quality/report.py --output docs/results/data-quality.md
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import sys
from pathlib import Path

import psycopg

# Half-hourly series: (label, table, WHERE clause). One row per half-hour is expected between each series'
# first and last period; generation mix has one row per fuel, so its half-hours are counted distinct.
HALF_HOURLY = [
    ("Carbon intensity, national actuals", "carbon_intensity", "region = 'GB' AND issued_at IS NULL"),
    ("Demand INDO (Elexon, initial)", "demand", "series = 'indo'"),
    ("Demand ITSDO (Elexon, initial)", "demand", "series = 'itsdo'"),
    ("Demand ND (NESO, settled)", "demand", "series = 'nd'"),
    ("Demand TSD (NESO, settled)", "demand", "series = 'tsd'"),
    ("Generation by fuel (FUELHH)", "generation_mix", "series = 'fuelhh'"),
    ("Embedded solar and wind (NESO)", "generation_mix", "series = 'embedded'"),
    ("System prices", "system_price", "true"),
    ("NESO day-ahead forecast (13:00 D-1)", "ndf_day_ahead", "true"),
]

CLOCK_CHANGES = {
    dt.date(2025, 3, 30): 46,
    dt.date(2025, 10, 26): 50,
    dt.date(2026, 3, 29): 46,
    dt.date(2026, 10, 25): 50,
}


def connect() -> psycopg.Connection:
    env = load_env(Path(".env"))
    return psycopg.connect(
        host=os.environ.get("POSTGRES_HOST", "localhost"),
        port=int(env.get("TIMESCALE_PORT", "5432")),
        dbname=env.get("POSTGRES_DB", "gridpulse"),
        user=env.get("POSTGRES_USER", "gridpulse"),
        password=env["POSTGRES_PASSWORD"],
        options="-c search_path=grid -c timezone=Europe/London",
    )


def load_env(path: Path) -> dict[str, str]:
    values = dict(os.environ)
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, _, value = line.partition("=")
                values.setdefault(key.strip(), value.split("#")[0].strip())
    return values


def table(headers: list[str], rows: list[tuple]) -> str:
    def cell(value: object) -> str:
        if isinstance(value, float):
            return f"{value:,.1f}"
        if isinstance(value, int):
            return f"{value:,}"
        if isinstance(value, dt.datetime):
            return value.strftime("%Y-%m-%d %H:%M")
        return "" if value is None else str(value)

    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines += ["| " + " | ".join(cell(v) for v in row) + " |" for row in rows]
    return "\n".join(lines)


def coverage(cur: psycopg.Cursor) -> tuple[str, list[str]]:
    rows, gap_sections = [], []
    for label, source, where in HALF_HOURLY:
        cur.execute(
            f"""
            WITH s AS (SELECT DISTINCT valid_from AS t FROM {source} WHERE {where}),
                 b AS (SELECT min(t) AS lo, max(t) AS hi, count(*) AS n FROM s),
                 missing AS (SELECT g AS t FROM b, generate_series(b.lo, b.hi, interval '30 minutes') g
                             EXCEPT SELECT t FROM s),
                 runs AS (SELECT min(t) AS first, max(t) AS last, count(*) AS periods
                          FROM (SELECT t, t - row_number() OVER (ORDER BY t) * interval '30 minutes' AS grp
                                FROM missing) x
                          GROUP BY grp)
            SELECT b.lo, b.hi, b.n, (SELECT coalesce(sum(periods), 0) FROM runs),
                   (SELECT count(*) FROM runs),
                   (SELECT coalesce(json_agg(json_build_array(first, last, periods) ORDER BY periods DESC), '[]')
                    FROM (SELECT * FROM runs ORDER BY periods DESC LIMIT 5) top)
            FROM b
            """
        )
        lo, hi, present, missing, runs, top = cur.fetchone()
        expected = present + missing
        rows.append((label, lo, hi, present, missing, runs, f"{100 * present / expected:.3f}%"))
        if runs:
            gap_sections.append(
                f"**{label}**: largest gaps\n\n"
                + table(
                    ["From (UTC)", "To (UTC)", "Half-hours"],
                    [(a[:16].replace("T", " "), b[:16].replace("T", " "), int(n)) for a, b, n in top],
                )
            )
    return (
        table(["Series", "First", "Last", "Present", "Missing", "Gaps", "Complete"], rows),
        gap_sections,
    )


def weather_coverage(cur: psycopg.Cursor) -> str:
    cur.execute(
        """
        SELECT series, CASE WHEN issued_at IS NULL THEN 'reanalysis' ELSE 'forecast' END AS kind,
               min(valid_from), max(valid_from), count(DISTINCT valid_from),
               (extract(epoch FROM max(valid_from) - min(valid_from)) / 3600)::int + 1
        FROM weather GROUP BY 1, 2 ORDER BY 2, 1
        """
    )
    rows = [
        (series, kind, lo, hi, n, expected - n, f"{100 * n / expected:.2f}%")
        for series, kind, lo, hi, n, expected in cur.fetchall()
    ]
    return table(["Location", "Kind", "First", "Last", "Hours", "Missing", "Complete"], rows)


def duplicates(cur: psycopg.Cursor) -> str:
    """Different event IDs for the same logical record would mean the identity rules (ADR-0004) leak."""
    checks = [
        ("carbon_intensity", "source, series, region, valid_from, issued_at"),
        ("demand", "source, series, region, valid_from, issued_at"),
        ("demand_forecast", "source, series, region, valid_from, issued_at"),
        ("generation_mix", "source, series, region, valid_from, issued_at, source_fuel_code"),
        ("system_price", "source, series, region, valid_from, issued_at"),
        ("weather", "source, series, region, valid_from, issued_at"),
    ]
    rows = []
    for name, key in checks:
        cur.execute(f"SELECT count(*) FROM (SELECT 1 FROM {name} GROUP BY {key} HAVING count(*) > 1) d")
        rows.append((name, key, cur.fetchone()[0]))
    return table(["Table", "Logical key", "Duplicated keys"], rows)


def clock_changes(cur: psycopg.Cursor) -> str:
    rows = []
    for day, expected in CLOCK_CHANGES.items():
        cur.execute(
            """
            SELECT series, count(*) FROM demand WHERE settlement_date = %s AND series IN ('indo','nd')
            GROUP BY series
            """,
            (day,),
        )
        counts = dict(cur.fetchall())
        cur.execute(
            "SELECT count(*) FROM carbon_intensity WHERE settlement_date = %s AND region = 'GB' AND issued_at IS NULL",
            (day,),
        )
        ci = cur.fetchone()[0]
        observed = [counts.get("indo"), counts.get("nd"), ci or None]
        verdict = "ok" if all(c in (None, expected) for c in observed) else "MISMATCH"
        rows.append((day.isoformat(), expected, *observed, verdict))
    return table(["Settlement date", "Expected", "INDO", "ND", "Carbon intensity", "Result"], rows)


def value_ranges(cur: psycopg.Cursor) -> str:
    cur.execute(
        """
        SELECT 'Demand ' || series, 'MW', min(demand_mw), avg(demand_mw), max(demand_mw) FROM demand GROUP BY series
        UNION ALL
        SELECT 'Carbon intensity actual', 'gCO2/kWh', min(actual_gco2_per_kwh), avg(actual_gco2_per_kwh),
               max(actual_gco2_per_kwh) FROM carbon_intensity WHERE region = 'GB' AND issued_at IS NULL
        UNION ALL
        SELECT 'System sell price', '£/MWh', min(system_sell_price_gbp_per_mwh), avg(system_sell_price_gbp_per_mwh),
               max(system_sell_price_gbp_per_mwh) FROM system_price
        UNION ALL
        SELECT 'Net imports (sum of interconnectors)', 'MW', min(v), avg(v), max(v)
        FROM (SELECT sum(value) AS v FROM generation_mix WHERE fuel = 'imports' GROUP BY valid_from) i
        UNION ALL
        SELECT 'Embedded solar', 'MW', min(value), avg(value), max(value) FROM generation_mix WHERE source_fuel_code = 'EMBEDDED_SOLAR_GENERATION'
        UNION ALL
        SELECT 'Temperature (reanalysis)', '°C', min(temperature_2m_c), avg(temperature_2m_c), max(temperature_2m_c)
        FROM weather WHERE issued_at IS NULL
        ORDER BY 1
        """
    )
    rows = [(name, unit, float(lo), float(mean), float(hi)) for name, unit, lo, mean, hi in cur.fetchall()]
    return table(["Measure", "Unit", "Min", "Mean", "Max"], rows)


def source_agreement(cur: psycopg.Cursor) -> str:
    """INDO is the initial outturn of ND (ADR-0007): they should agree closely, and settled ND is the truth."""
    cur.execute(
        """
        SELECT count(*), avg(i.demand_mw - n.demand_mw), avg(abs(i.demand_mw - n.demand_mw)),
               100 * avg(abs(i.demand_mw - n.demand_mw) / n.demand_mw),
               100 * avg((abs(i.demand_mw - n.demand_mw) / n.demand_mw <= 0.02)::int)
        FROM demand i JOIN demand n ON n.valid_from = i.valid_from AND n.series = 'nd'
        WHERE i.series = 'indo'
        """
    )
    n, bias, mae, mape, within = cur.fetchone()
    return table(
        ["Comparison", "Half-hours", "Mean difference (MW)", "MAE (MW)", "MAPE", "Within 2%"],
        [("INDO (initial) vs ND (settled)", n, bias, mae, f"{mape:.2f}%", f"{within:.1f}%")],
    )


def benchmark(cur: psycopg.Cursor) -> tuple[str, str]:
    """NESO's day-ahead forecast vs actual ND, and a seasonal-naive forecast on the same half-hours."""

    def metrics(forecast: str) -> str:
        error = f"({forecast} - actual)"
        return (
            f"count(*), avg({error}), avg(abs({error})), sqrt(avg({error} ^ 2)), "
            f"100 * avg(abs({error}) / actual)"
        )

    base = """
        WITH x AS (
            SELECT a.valid_from, a.demand_mw AS actual, d.forecast_mw AS ndf, w.demand_mw AS week_ago
            FROM national_demand_actual a
            JOIN ndf_day_ahead d ON d.valid_from = a.valid_from
            JOIN national_demand_actual w ON w.valid_from = a.valid_from - interval '7 days'
        )
    """
    cur.execute(
        base
        + f"""
        SELECT 'NESO NDF, published by 13:00 D-1', {metrics("ndf")} FROM x
        UNION ALL
        SELECT 'Seasonal naive (same half-hour last week)', {metrics("week_ago")} FROM x
        """
    )
    overall = [
        (name, n, bias, mae, rmse, f"{mape:.2f}%") for name, n, bias, mae, rmse, mape in cur.fetchall()
    ]
    cur.execute(
        base
        + """
        SELECT to_char(valid_from, 'YYYY-MM') AS month, count(*),
               100 * avg(abs(ndf - actual) / actual), 100 * avg(abs(week_ago - actual) / actual)
        FROM x GROUP BY 1 ORDER BY 1
        """
    )
    monthly = [(m, n, f"{a:.2f}%", f"{b:.2f}%") for m, n, a, b in cur.fetchall()]
    return (
        table(["Forecast", "Half-hours", "Bias (MW)", "MAE (MW)", "RMSE (MW)", "MAPE"], overall),
        table(["Month", "Half-hours", "NESO NDF MAPE", "Seasonal-naive MAPE"], monthly),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, help="write Markdown here instead of stdout")
    args = parser.parse_args()

    with connect() as conn, conn.cursor() as cur:
        series_table, gap_sections = coverage(cur)
        overall, monthly = benchmark(cur)
        report = "\n\n".join(
            [
                "# GridPulse data-quality report",
                f"Generated {dt.datetime.now(dt.UTC):%Y-%m-%d %H:%M} UTC by `tools/data-quality/report.py`. "
                "Times are UK local. Regenerate after loading data; numbers below are from the run that "
                "produced this file.",
                "## Coverage of half-hourly series",
                "Each series is checked from its own first to last half-hour, so NESO's publication lag "
                "isn't counted as a gap.",
                series_table,
                *gap_sections,
                "## Weather coverage",
                weather_coverage(cur),
                "## Duplicates",
                "Records sharing a logical key under different event IDs. Should all be zero (ADR-0004).",
                duplicates(cur),
                "## Clock-change days",
                "A UK settlement day has 46 periods when the clocks go forward and 50 when they go back.",
                clock_changes(cur),
                "## Value ranges",
                value_ranges(cur),
                "## Agreement between sources",
                source_agreement(cur),
                "## The day-ahead benchmark (ADR-0010)",
                "NESO's day-ahead National Demand forecast, as published by 13:00 UK time the day before, "
                "against actual ND (settled where NESO has published it, otherwise INDO). A seasonal-naive "
                "forecast on the same half-hours is the floor any model must clear. "
                "**This is the number Phase 2 has to beat.**",
                overall,
                "### By month",
                monthly,
            ]
        )
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report + "\n", encoding="utf-8")
        print(f"wrote {args.output}")
    else:
        print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
