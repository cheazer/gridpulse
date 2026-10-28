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
[[ $count -eq 12 ]] && pass "12 gridpulse topics (6 event + 6 dlq)" || fail "expected 12 topics, found $count: $topics"
autocreate=$(docker compose exec -T redpanda rpk cluster config get auto_create_topics_enabled)
[[ $autocreate == false ]] && pass "topic auto-creation disabled" || fail "auto_create_topics_enabled=$autocreate"
partitions=$(docker compose exec -T redpanda rpk topic describe gridpulse.demand.v1 -p | awk 'NR>1' | grep -c .)
[[ $partitions -eq 6 ]] && pass "gridpulse.demand.v1 has 6 partitions" || fail "gridpulse.demand.v1 has $partitions partitions"

echo "--- schema registry"
subjects=$(curl -fsS localhost:18081/subjects)
for s in gridpulse.envelope.v1 gridpulse.carbon-intensity.v1-value gridpulse.demand-forecast.v1-value gridpulse.weather.v1-value; do
  grep -q "\"$s\"" <<<"$subjects" && pass "subject $s" || fail "subject $s missing"
done
compat=$(curl -fsS localhost:18081/config/gridpulse.demand.v1-value)
grep -q BACKWARD <<<"$compat" && pass "BACKWARD compatibility on gridpulse.demand.v1-value" || fail "compatibility: $compat"
refs=$(curl -fsS localhost:18081/subjects/gridpulse.demand-forecast.v1-value/versions/latest)
grep -q 'demand.schema.json' <<<"$refs" && grep -q 'envelope.schema.json' <<<"$refs" \
  && pass "demand-forecast schema references envelope + demand" || fail "demand-forecast references: $refs"

echo "--- kafka round trip"
example=schemas/events/v1/examples/valid/carbon-intensity-national-actual.json
event_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["event_id"])' "$example")
python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))))' "$example" \
  | docker compose exec -T redpanda rpk topic produce gridpulse.carbon-intensity.v1 -k GB >/dev/null
received=$(docker compose exec -T redpanda rpk topic consume gridpulse.carbon-intensity.v1 -o start -n 1 -f '%k %v\n')
grep -q "^GB .*$event_id" <<<"$received" && pass "golden event produced and consumed with key GB" || fail "round trip: $received"

echo "--- timescaledb"
version=$(docker compose exec -T timescaledb psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
  "SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'")
[[ -n $version ]] && pass "timescaledb extension $version" || fail "timescaledb extension not installed"
bash db/tests/run.sh && pass "migrations apply, re-run as a no-op, and schema assertions hold" || fail "db/tests/run.sh"

echo
echo "$failures failure(s)"
exit $((failures > 0))
