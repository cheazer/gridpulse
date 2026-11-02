"""End-to-end test of the running stack: data in at the front, the right answer out at the back.

Plants a carbon-intensity profile with a known cleanest window and follows it through every service:

    NESO events -> Kafka -> timescale-sink -> TimescaleDB -> scheduling API -> dashboard
    GridPulse forecasts (as the forecast job's login) -> TimescaleDB -> forecasting API -> scheduling API

The live pollers and the training job are left out: they depend on public APIs and a trained model, which would make
the test flaky and slow without testing any more of the wiring. Everything else is the real images, built from this
commit, talking over the Compose network.

Run from the repository root with the stack up (see .github/workflows/e2e.yml):

    python3 tests/e2e/end_to_end.py

Standard library only, so it runs on any CI runner without installing anything.
"""

from __future__ import annotations

import datetime as dt
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from zoneinfo import ZoneInfo

SCHEDULER = "http://localhost:8100"
FORECASTING = "http://localhost:8000"
DASHBOARD = "http://localhost:8200"
TOPIC = "gridpulse.carbon-intensity.v1"
# The ingestion service's namespace for event IDs (EventIds.java): the sink accepts only UUIDv5s.
EVENT_NAMESPACE = uuid.UUID("4402d35f-92c6-4368-bf94-8f364945a3e4")
UK = ZoneInfo("Europe/London")

HALF_HOUR = dt.timedelta(minutes=30)
NOW = dt.datetime.now(dt.UTC).replace(microsecond=0)
START = NOW.replace(minute=NOW.minute - NOW.minute % 30, second=0)  # the current half-hour

# The planted profiles: a flat 200 g/kWh with one 2-hour dip, at a different time in each source, so each answer
# proves which forecast it came from.
FLAT, DIP = 200.0, 60.0
NESO_DIP = range(20, 24)  # 10:00 to 12:00 hours from now
GRIDPULSE_DIP = range(30, 34)  # 15:00 to 17:00 hours from now
HALF_HOURS = 48


def main() -> None:
    plant_neso_events()
    plant_gridpulse_forecasts()

    step("the sink writes NESO's forecast to TimescaleDB, and the scheduling API reads it back")
    timeline = wait_for(lambda: complete_timeline(), "NESO's forecast to arrive through Kafka and the sink")
    check(not timeline["forecast"]["stale"], "NESO's forecast is fresh")

    step("the scheduling API finds the planted NESO dip")
    neso = schedule("neso")
    check(instant(neso["best"]["start"]) == START + NESO_DIP[0] * HALF_HOUR, f"best window starts at the dip: {neso['best']}")
    check(neso["best"]["intensity"] == DIP, "at the dip's intensity")
    # 10 kWh at 200 rather than 60 g/kWh: 1.4 kg saved.
    check(abs(neso["savingKg"] - 1.4) < 0.01, f"saves 1.4 kg: {neso['savingKg']}")

    step("the forecasting API serves GridPulse's forecast, and the scheduler uses its range")
    carbon = get_json(f"{FORECASTING}/v1/forecasts/carbon")
    check(not carbon["stale"] and len(carbon["half_hours"]) >= HALF_HOURS - 1, "forecasting API has a fresh forecast")
    gridpulse = schedule("gridpulse")
    expected = START + GRIDPULSE_DIP[0] * HALF_HOUR
    check(instant(gridpulse["best"]["start"]) == expected, f"best window starts at GridPulse's dip: {gridpulse['best']}")
    low, high = gridpulse["savingLowKg"], gridpulse["savingHighKg"]
    # 10 kWh saving 140 g/kWh at P50, 112 at P10, 182 at P90: 1.12 to 1.82 kg around 1.4.
    check(abs(low - 1.12) < 0.01 and abs(high - 1.82) < 0.01, f"a range of 1.12 to 1.82 kg: {low} to {high}")

    step("actuals flow through to the scheduling API")
    actuals = get_json(f"{SCHEDULER}/v1/carbon/GB/actual")["halfHours"]
    check(len(actuals) == 8 and all(a["intensity"] == 180 for a in actuals), f"8 actuals of 180: {actuals}")

    step("the dashboard shows it, in UK time")
    home = get_text(f"{DASHBOARD}/")
    check('card-value">200' in home, "now: 200 g/kWh")
    check('card-value">60' in home, "cleanest in the next 24 hours: 60 g/kWh")
    dip_uk = (START + NESO_DIP[0] * HALF_HOUR).astimezone(UK).strftime("%a %H:%M")
    check(f"{dip_uk} (UK time)" in home, f"at {dip_uk} UK time")
    check("Must finish by (UK time)" in get_text(f"{DASHBOARD}/schedule"), "the schedule page renders")

    print("\nEnd to end: all checks passed.")


# ---------------------------------------------------------------------------------------------------------- planting


def plant_neso_events() -> None:
    """NESO's national forecast for the next 24 hours and the last 4 hours' actuals, as the ingestion service sends them."""
    step(f"publishing NESO events to {TOPIC}")
    issued = NOW - dt.timedelta(minutes=5)
    forecasts = [
        event(START + i * HALF_HOUR, issued, forecast=DIP if i in NESO_DIP else FLAT, actual=None)
        for i in range(HALF_HOURS)
    ]
    actuals = [event(START - i * HALF_HOUR, None, forecast=180, actual=180) for i in range(1, 9)]
    lines = "".join(json.dumps(e, separators=(",", ":")) + "\n" for e in forecasts + actuals)
    # Keyed by region, like the ingestion service, so a region's events stay in order on one partition.
    compose("exec", "-T", "redpanda", "rpk", "topic", "produce", TOPIC, "-k", "GB", stdin=lines)


def event(valid_from: dt.datetime, issued_at: dt.datetime | None, forecast: float, actual: float | None) -> dict:
    uk_day = valid_from.astimezone(UK).date()
    uk_midnight = dt.datetime.combine(uk_day, dt.time(), UK).astimezone(dt.UTC)
    issued = iso(issued_at) if issued_at else None
    return {
        "event_id": str(uuid.uuid5(EVENT_NAMESPACE, f"e2e|GB|{iso(valid_from)}|{issued}")),
        "schema_version": "1.0.0",
        "event_type": "carbon_intensity",
        "source": "neso-carbon-intensity",
        "series": "intensity",
        "region": "GB",
        "valid_from": iso(valid_from),
        "valid_to": iso(valid_from + HALF_HOUR),
        # Settlement periods count half-hours from UK midnight, so clock-change days have 46 or 50 of them.
        "settlement_date": uk_day.isoformat(),
        "settlement_period": (valid_from - uk_midnight) // HALF_HOUR + 1,
        "issued_at": issued,
        "ingested_at": iso(NOW),
        "payload": {
            "forecast_gco2_per_kwh": forecast,
            "actual_gco2_per_kwh": actual,
            "index": "low" if forecast < 100 else "high",
        },
    }


def plant_gridpulse_forecasts() -> None:
    """A 48-hour carbon forecast with a P10-P90 band, written as the forecast job's own login, with its privileges.

    The band is proportional (-20%/+30%), so the saving differs at each level and the range is a real one; a band of
    constant width would save the same at every level and hide a range that was never worked out.
    """
    step("writing a GridPulse forecast as the forecast_job login")
    issued = START - dt.timedelta(minutes=1)  # a forecast never starts before its issue time
    rows = []
    for i in range(HALF_HOURS):
        p50 = DIP if i in GRIDPULSE_DIP else FLAT
        start = START + i * HALF_HOUR
        rows.append(
            f"('carbon', 'e2e', 'e2e', '{iso(issued)}', '{iso(start)}', '{iso(start + HALF_HOUR)}', "
            f"'gCO2/kWh', {p50 * 0.8}, {p50}, {p50 * 1.3})"
        )
    sql = (
        "INSERT INTO grid.model_forecast (task, model, model_version, issued_at, valid_from, valid_to, unit, p10, p50, p90) "
        f"VALUES {', '.join(rows)};"
    )
    password = env_file()["FORECAST_JOB_DB_PASSWORD"]
    database = env_file().get("POSTGRES_DB", "gridpulse")
    compose(
        "exec", "-T", "-e", f"PGPASSWORD={password}", "timescaledb",
        "psql", "-v", "ON_ERROR_STOP=1", "-q", "-h", "localhost", "-U", "forecast_job", "-d", database, "-c", sql,
    )


# ----------------------------------------------------------------------------------------------------------- helpers


def complete_timeline() -> dict | None:
    timeline = get_json(f"{SCHEDULER}/v1/carbon/GB?source=neso")
    return timeline if len(timeline["halfHours"]) >= HALF_HOURS - 1 else None  # the first may have just ended


def schedule(source: str) -> dict:
    body = {
        "durationMinutes": 120,
        "energyKwh": 10,
        "deadline": iso(START + 20 * dt.timedelta(hours=1)),
        "region": "GB",
        "source": source,
    }
    request = urllib.request.Request(
        f"{SCHEDULER}/v1/schedule", json.dumps(body).encode(), {"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        fail(f"POST /v1/schedule ({source}) gave {e.code}: {e.read().decode()}")
        raise


def wait_for(probe, what: str, seconds: int = 180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if (result := probe()) is not None:
                return result
        except (urllib.error.URLError, KeyError, json.JSONDecodeError):
            pass
        time.sleep(3)
    fail(f"timed out after {seconds}s waiting for {what}")


def get_json(url: str):
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.load(response)


def get_text(url: str) -> str:
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read().decode()


def compose(*args: str, stdin: str | None = None) -> None:
    result = subprocess.run(["docker", "compose", *args], input=stdin, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        fail(f"docker compose {args[0]} {args[2] if len(args) > 2 else ''} failed:\n{result.stdout}{result.stderr}")


def env_file() -> dict[str, str]:
    pairs = (line.split("=", 1) for line in Path(".env").read_text().splitlines() if "=" in line and not line.startswith("#"))
    return {k.strip(): v.strip() for k, v in pairs}


def instant(text: str) -> dt.datetime:
    """A timestamp from an API as an aware datetime: they write offsets as "Z" or "+00:00", the same instant."""
    return dt.datetime.fromisoformat(text)


def iso(time: dt.datetime) -> str:
    return time.astimezone(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def step(text: str) -> None:
    print(f"\n== {text}", flush=True)


def check(condition: bool, text: str) -> None:
    if not condition:
        fail(text)
    print(f"   ok  {text}", flush=True)


def fail(text: str) -> None:
    print(f"   FAIL  {text}", flush=True)
    sys.exit(1)


if __name__ == "__main__":
    main()
