#!/usr/bin/env bash
# End-to-end check of the local infrastructure. Run from the repo root:
#   bash deploy/compose/scripts/smoke-test.sh
# Starts the stack, runs the init jobs, then verifies topics, schemas, a Kafka
# round trip of a golden event, and the TimescaleDB extension.
set -uo pipefail

failures=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }

set -a; source .env; set +a

echo "--- starting infrastructure"
docker compose up -d --wait redpanda timescaledb console || { echo "stack failed to start"; exit 1; }

echo "--- init jobs"
docker compose run --rm redpanda-init && pass "redpanda-init exited 0" || fail "redpanda-init"
docker compose run --rm schema-init && pass "schema-init exited 0" || fail "schema-init"

echo "--- topics"
topics=$(docker compose exec -T redpanda rpk topic list | awk 'NR>1 {print $1}' | grep '^gridpulse\.' | sort)
count=$(grep -c . <<<"$topics")
[[ $count -eq 13 ]] && pass "13 gridpulse topics (6 event + 6 dlq + backfill checkpoints)" || fail "expected 13 topics, found $count: $topics"
autocreate=$(docker compose exec -T redpanda rpk cluster config get auto_create_topics_enabled)
[[ $autocreate == false ]] && pass "topic auto-creation disabled" || fail "auto_create_topics_enabled=$autocreate"
partitions=$(docker compose exec -T redpanda rpk topic describe gridpulse.demand.v1 -p | awk 'NR>1' | grep -c .)
[[ $partitions -eq 6 ]] && pass "gridpulse.demand.v1 has 6 partitions" || fail "gridpulse.demand.v1 has $partitions partitions"

echo "--- schema registry"
subjects=$(curl -fsS localhost:18081/subjects)
count=$(grep -o '"gridpulse\.[a-z-]*\.v1-value"' <<<"$subjects" | wc -l)
[[ $count -eq 6 ]] && pass "6 event schema subjects" || fail "expected 6 subjects, got $count: $subjects"
compat=$(curl -fsS localhost:18081/config/gridpulse.demand.v1-value)
grep -q BACKWARD <<<"$compat" && pass "BACKWARD compatibility on gridpulse.demand.v1-value" || fail "compatibility: $compat"
# Registered schemas are bundled (ADR-0002): no $ref may point outside the document.
external=$(curl -fsS localhost:18081/subjects/gridpulse.demand-forecast.v1-value/versions/latest | python3 -c '
import json, sys
refs = []
def walk(node):
    if isinstance(node, dict):
        for k, v in node.items():
            refs.append(v) if k == "$ref" else walk(v)
    elif isinstance(node, list):
        for v in node: walk(v)
schema = json.loads(json.load(sys.stdin)["schema"])
walk(schema)
# Redpanda stores internal refs resolved against the schema own $id; those are still internal.
own = schema.get("$id", "") + "#"
print(" ".join(r for r in refs if not (r.startswith("#") or r.startswith(own))))')
[[ -z $external ]] && pass "registered demand-forecast schema is self-contained" \
  || fail "demand-forecast schema has external \$refs: $external"

echo "--- kafka round trip"
# Uses the dead-letter topic, which nothing consumes, so the golden event can't leak into a live
# database; reads back the exact partition and offset written, as the topic may hold other records.
topic=gridpulse.carbon-intensity.v1.dlq
example=schemas/events/v1/examples/valid/carbon-intensity-national-actual.json
event_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["event_id"])' "$example")
produced=$(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))))' "$example" \
  | docker compose exec -T redpanda rpk topic produce "$topic" -k GB)
partition=$(sed -nE 's/.*partition ([0-9]+) at offset ([0-9]+).*/\1/p' <<<"$produced")
offset=$(sed -nE 's/.*partition ([0-9]+) at offset ([0-9]+).*/\2/p' <<<"$produced")
received=$(docker compose exec -T redpanda rpk topic consume "$topic" \
  -p "$partition" -o "$offset" -n 1 -f '%k %v\n')
grep -q "^GB .*$event_id" <<<"$received" && pass "golden event produced and consumed with key GB" \
  || fail "round trip ($produced): $received"

echo "--- timescaledb"
version=$(docker compose exec -T timescaledb psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
  "SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'")
[[ -n $version ]] && pass "timescaledb extension $version" || fail "timescaledb extension not installed"
bash db/tests/run.sh && pass "migrations apply, re-run as a no-op, and schema assertions hold" || fail "db/tests/run.sh"

echo
echo "$failures failure(s)"
exit $((failures > 0))
