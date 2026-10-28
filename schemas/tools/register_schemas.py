"""Register the v1 event schemas with the Kafka schema registry.

Subjects follow the TopicNameStrategy: '<topic>-value', where the topic is derived from the
schema file name (carbon-intensity.schema.json -> gridpulse.carbon-intensity.v1).

Each schema is *bundled* before registering: every cross-file $ref (to the envelope, or to
another type's definitions) is inlined under "definitions", giving one self-contained document per
subject. Redpanda's registry stores schemas with references, but its compatibility checker can't
resolve them (ADR-0002), and self-contained schemas are simpler for any consumer anyway.

Re-running is safe: registering an identical schema returns the existing version, and a changed
one is accepted only if it is BACKWARD-compatible with the latest version.

Standard library only, so it runs in a bare python image:
    SCHEMA_REGISTRY_URL=http://localhost:18081 python schemas/tools/register_schemas.py
"""

from __future__ import annotations

import copy
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

SCHEMA_DIR = Path(__file__).resolve().parents[1] / "events" / "v1"
REGISTRY_URL = os.environ.get("SCHEMA_REGISTRY_URL", "http://localhost:18081").rstrip("/")
COMPATIBILITY = "BACKWARD"  # ADR-0002
SHARED_ONLY = {"envelope.schema.json"}  # inlined into others, not a topic of its own


def load_schemas() -> dict[str, dict]:
    return {p.name: json.loads(p.read_text(encoding="utf-8")) for p in SCHEMA_DIR.glob("*.schema.json")}


def bundle(name: str, schemas: dict[str, dict]) -> dict:
    """Return schema `name` with every external $ref inlined under "definitions".

    "envelope.schema.json"                    -> "#/definitions/envelope"
    "demand.schema.json#/definitions/x"       -> "#/definitions/demand/definitions/x"
    and, inside an inlined document, "#/y"    -> "#/definitions/<its key>/y"
    """

    def key(file_name: str) -> str:
        return file_name.removesuffix(".schema.json")

    needed: list[str] = []

    def fix(ref: str, prefix: str) -> str:
        if ref.startswith("#"):
            return ref if prefix == "#" else prefix + ref[1:]
        file_name, _, fragment = ref.partition("#")
        if file_name not in needed:
            needed.append(file_name)
        return f"#/definitions/{key(file_name)}{fragment}"

    def rewrite(node: object, prefix: str) -> None:
        if isinstance(node, dict):
            for k, v in node.items():
                if k == "$ref" and isinstance(v, str):
                    node[k] = fix(v, prefix)
                else:
                    rewrite(v, prefix)
        elif isinstance(node, list):
            for item in node:
                rewrite(item, prefix)

    root = copy.deepcopy(schemas[name])
    rewrite(root, "#")
    definitions = root.setdefault("definitions", {})
    i = 0
    while i < len(needed):  # inlined documents may themselves reference other files
        dependency = copy.deepcopy(schemas[needed[i]])
        dependency.pop("$id", None)  # an embedded $id would change how its refs resolve
        dependency.pop("$schema", None)
        rewrite(dependency, f"#/definitions/{key(needed[i])}")
        definitions[key(needed[i])] = dependency
        i += 1
    return root


def subject_for(file_name: str) -> str:
    return f"gridpulse.{file_name.removesuffix('.schema.json')}.v1-value"


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
    schemas = load_schemas()
    for name in sorted(n for n in schemas if n not in SHARED_ONLY):
        subject = subject_for(name)
        call("PUT", f"/config/{subject}", {"compatibility": COMPATIBILITY})
        result = call(
            "POST",
            f"/subjects/{subject}/versions",
            {"schemaType": "JSON", "schema": json.dumps(bundle(name, schemas))},
        )
        latest = call("GET", f"/subjects/{subject}/versions/latest")
        if latest["id"] != result["id"]:
            raise RuntimeError(f"{subject}: registered id {result['id']} is not the latest ({latest['id']})")
        print(f"{subject:45} v{latest['version']} (id {result['id']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
