#!/usr/bin/env bash
# Create GridPulse topics idempotently. Safe to re-run: existing topics are left untouched.
set -euo pipefail

PARTITIONS=6               # region is the key; 6 partitions lets up to 6 sink consumers share the load
RETENTION_MS=604800000     # 7 days: TimescaleDB is the system of record, Kafka is the replay buffer
DLQ_RETENTION_MS=2592000000  # 30 days: time to inspect and replay bad records

EVENT_TOPICS=(
  gridpulse.carbon-intensity.v1
  gridpulse.generation-mix.v1
  gridpulse.demand.v1
  gridpulse.demand-forecast.v1
  gridpulse.system-price.v1
  gridpulse.weather.v1
)

create() {
  local topic=$1 partitions=$2 retention=$3
  local output
  if output=$(rpk topic create "$topic" -p "$partitions" -r 1 \
        -c cleanup.policy=delete -c retention.ms="$retention" 2>&1); then
    echo "created  $topic"
  elif grep -q TOPIC_ALREADY_EXISTS <<<"$output"; then
    echo "exists   $topic"
  else
    echo "$output" >&2
    return 1
  fi
}

for topic in "${EVENT_TOPICS[@]}"; do
  create "$topic" "$PARTITIONS" "$RETENTION_MS"
  create "$topic.dlq" 1 "$DLQ_RETENTION_MS"
done
