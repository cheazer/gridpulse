"""Register the v1 event schemas with the Kafka schema registry.

Subjects follow the TopicNameStrategy: '<topic>-value', where the topic is derived
from the schema file name (carbon-intensity.schema.json -> gridpulse.carbon-intensity.v1).
Shared schemas that are only referenced (the envelope) get a 'gridpulse.<name>.v1' subject.

Cross-file $refs become registry references, so dependencies are registered first.
Re-running is safe: registering an identical schema returns the existing version.

Standard library only, so it runs in a bare python image:
    SCHEMA_REGISTRY_URL=http://localhost:18081 python schemas/tools/register_schemas.py
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

SCHEMA_DIR = Path(__file__).resolve().parents[1] / "events" / "v1"
REGISTRY_URL = os.environ.get("SCHEMA_REGISTRY_URL", "http://localhost:18081").rstrip("/")
COMPATIBILITY = "BACKWARD"  # ADR-0002
REFERENCE_ONLY = {"envelope.schema.json"}


def subject_for(file_name: str) -> str:
    stem = file_name.removesuffix(".schema.json")
    if file_name in REFERENCE_ONLY:
        return f"gridpulse.{stem}.v1"
    return f"gridpulse.{stem}.v1-value"


def external_refs(node: object) -> set[str]:
    """File names of every non-local $ref in a schema (e.g. 'envelope.schema.json')."""
    found: set[str] = set()
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str) and not ref.startswith("#"):
            found.add(ref.split("#", 1)[0])
        for value in node.values():
            found |= external_refs(value)
    elif isinstance(node, list):
        for value in node:
            found |= external_refs(value)
    return found


def registration_order(deps: dict[str, set[str]]) -> list[str]:
    ordered: list[str] = []

    def visit(name: str, path: tuple[str, ...]) -> None:
        if name in ordered:
            return
        if name in path:
            raise ValueError(f"circular schema reference: {' -> '.join(path + (name,))}")
        for dep in sorted(deps[name]):
            visit(dep, path + (name,))
        ordered.append(name)

    for name in sorted(deps):
        visit(name, ())
    return ordered


def call(method: str, path: str, body: dict | None = None) -> dict:
    request = urllib.request.Request(
        f"{REGISTRY_URL}{path}",
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/vnd.schemaregistry.v1+json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{method} {path} -> {error.code}: {error.read().decode()}") from error


def main() -> int:
    texts = {p.name: p.read_text(encoding="utf-8") for p in SCHEMA_DIR.glob("*.schema.json")}
    deps = {name: external_refs(json.loads(text)) for name, text in texts.items()}
    versions: dict[str, int] = {}

    for name in registration_order(deps):
        subject = subject_for(name)
        references = [
            {"name": dep, "subject": subject_for(dep), "version": versions[dep]} for dep in sorted(deps[name])
        ]
        call("PUT", f"/config/{subject}", {"compatibility": COMPATIBILITY})
        result = call(
            "POST",
            f"/subjects/{subject}/versions",
            {"schemaType": "JSON", "schema": texts[name], "references": references},
        )
        latest = call("GET", f"/subjects/{subject}/versions/latest")
        if latest["id"] != result["id"]:
            raise RuntimeError(f"{subject}: registered id {result['id']} is not the latest ({latest['id']})")
        versions[name] = latest["version"]
        refs = f"  refs: {', '.join(r['name'] for r in references)}" if references else ""
        print(f"{subject:45} v{latest['version']} (id {result['id']}){refs}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
