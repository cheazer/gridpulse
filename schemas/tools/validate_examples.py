# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "jsonschema>=4.23",
#     "referencing>=0.35",
#     "rfc3339-validator>=0.1.4",
#     "tzdata>=2024.1",
# ]
# ///
"""Validate the golden example events against the v1 schemas.

Two layers of checks:
  schema   - JSON Schema validation (structure, types, enums, UTC pattern).
  semantic - rules JSON Schema can't express: event_id derivation, interval
             ordering and settlement-period consistency with Europe/London time.

Every file in examples/valid must pass both layers. Every file in
examples/invalid must fail the layer named in its `expect_failure` (and, for
semantic cases, the specific `expect_check`), so the checks themselves are tested.

Run from the repo root:  uv run schemas/tools/validate_examples.py
"""

from __future__ import annotations

import json
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from jsonschema import Draft7Validator
from referencing import Registry, Resource

SCHEMA_DIR = Path(__file__).resolve().parents[1] / "events" / "v1"
EXAMPLES_DIR = SCHEMA_DIR / "examples"

GRIDPULSE_NAMESPACE = uuid.UUID("4402d35f-92c6-4368-bf94-8f364945a3e4")
LONDON = ZoneInfo("Europe/London")
SETTLEMENT_PERIOD = timedelta(minutes=30)

SCHEMA_FILE_BY_EVENT_TYPE = {
    "carbon_intensity": "carbon-intensity.schema.json",
    "generation_mix": "generation-mix.schema.json",
    "demand": "demand.schema.json",
    "demand_forecast": "demand-forecast.schema.json",
    "system_price": "system-price.schema.json",
    "weather": "weather.schema.json",
}


def derive_event_id(event: dict) -> str:
    name = "|".join(
        [
            event["source"],
            event["event_type"],
            event["series"],
            event["region"],
            event["valid_from"],
            event["issued_at"] or "",
        ]
    )
    return str(uuid.uuid5(GRIDPULSE_NAMESPACE, name))


def parse_utc(value: str) -> datetime:
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def settlement_date_and_period(instant: datetime) -> tuple[str, int]:
    """Map a UTC instant to the GB settlement date and 1-based period it falls in."""
    local_date = instant.astimezone(LONDON).date()
    local_midnight = datetime(local_date.year, local_date.month, local_date.day, tzinfo=LONDON)
    # Subtract in UTC: Python subtracts aware datetimes that share a tzinfo using
    # wall-clock time, which would hide the missing/extra hour on clock-change days.
    elapsed = instant - local_midnight.astimezone(timezone.utc)
    return local_date.isoformat(), elapsed // SETTLEMENT_PERIOD + 1


def semantic_errors(event: dict) -> dict[str, str]:
    """Return {check_name: message} for every semantic rule the event breaks."""
    errors: dict[str, str] = {}

    expected_id = derive_event_id(event)
    if event["event_id"] != expected_id:
        errors["event_id"] = f"expected {expected_id}, got {event['event_id']}"

    valid_from, valid_to = parse_utc(event["valid_from"]), parse_utc(event["valid_to"])
    if valid_to <= valid_from:
        errors["interval"] = "valid_to must be after valid_from"

    date, period = event["settlement_date"], event["settlement_period"]
    if (date is None) != (period is None):
        errors["settlement"] = "settlement_date and settlement_period must both be set or both be null"
    elif period is not None:
        expected = settlement_date_and_period(valid_from)
        if (date, period) != expected:
            errors["settlement"] = f"expected {expected[0]} SP{expected[1]}, got {date} SP{period}"
        elif "interval" not in errors and valid_to - valid_from != SETTLEMENT_PERIOD:
            errors["settlement"] = "settlement-period events must span exactly 30 minutes"

    return errors


def build_validators() -> dict[str, Draft7Validator]:
    schemas = [json.loads(p.read_text(encoding="utf-8")) for p in SCHEMA_DIR.glob("*.schema.json")]
    registry = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in schemas)
    by_file = {Path(s["$id"]).name: s for s in schemas}

    validators = {}
    for event_type, file_name in SCHEMA_FILE_BY_EVENT_TYPE.items():
        schema = by_file[file_name]
        Draft7Validator.check_schema(schema)
        validators[event_type] = Draft7Validator(
            schema, registry=registry, format_checker=Draft7Validator.FORMAT_CHECKER
        )
    return validators


def schema_errors(event: dict, validators: dict[str, Draft7Validator]) -> list[str]:
    validator = validators.get(event.get("event_type"))
    if validator is None:
        return [f"unknown event_type {event.get('event_type')!r}"]
    return [f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message}" for e in validator.iter_errors(event)]


def check_valid(path: Path, validators: dict[str, Draft7Validator]) -> list[str]:
    event = json.loads(path.read_text(encoding="utf-8"))
    problems = schema_errors(event, validators)
    if not problems:  # semantic checks assume a structurally valid event
        problems = [f"{k}: {v}" for k, v in semantic_errors(event).items()]
    return problems


def check_invalid(path: Path, validators: dict[str, Draft7Validator]) -> list[str]:
    case = json.loads(path.read_text(encoding="utf-8"))
    event, expected_layer = case["event"], case["expect_failure"]

    found_schema = schema_errors(event, validators)
    if expected_layer == "schema":
        return [] if found_schema else ["expected a schema error, but the event passed schema validation"]

    if found_schema:
        return [f"expected only a semantic failure, but schema validation failed: {found_schema}"]
    found_semantic = semantic_errors(event)
    expected_check = case["expect_check"]
    if expected_check not in found_semantic:
        return [f"expected semantic check {expected_check!r} to fail; failures were {sorted(found_semantic) or 'none'}"]
    unexpected = set(found_semantic) - {expected_check}
    if unexpected:
        return [f"case should fail only {expected_check!r}, but {sorted(unexpected)} also failed"]
    return []


def main() -> int:
    validators = build_validators()
    failures = 0
    for kind, checker in (("valid", check_valid), ("invalid", check_invalid)):
        for path in sorted((EXAMPLES_DIR / kind).glob("*.json")):
            problems = checker(path, validators)
            status = "ok  " if not problems else "FAIL"
            print(f"{status} {kind}/{path.name}")
            for problem in problems:
                print(f"       {problem}")
            failures += bool(problems)
    print(f"\n{failures} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
