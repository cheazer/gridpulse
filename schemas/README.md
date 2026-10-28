# Event schemas

The contract between every GridPulse producer and consumer. Rationale: [ADR-0002](../docs/adr/0002-redpanda-and-json-schema.md)
(format and compatibility) and [ADR-0004](../docs/adr/0004-event-identity-and-time.md) (identity and time).

```
events/v1/
  envelope.schema.json          fields shared by every event
  <type>.schema.json            one per event_type: const event_type + closed payload
  examples/valid/*.json         golden events: must pass every check
  examples/invalid/*.json       {expect_failure, expect_check?, reason, event}: must fail the named check
tools/validate_examples.py      runs both layers over all examples
```

## Topics

| event_type | Kafka topic | Partition key |
|---|---|---|
| `carbon_intensity` | `gridpulse.carbon-intensity.v1` | `region` |
| `generation_mix` | `gridpulse.generation-mix.v1` | `region` |
| `demand` | `gridpulse.demand.v1` | `region` |
| `demand_forecast` | `gridpulse.demand-forecast.v1` | `region` |
| `system_price` | `gridpulse.system-price.v1` | `region` |
| `weather` | `gridpulse.weather.v1` | `region` |

Each topic has a dead-letter sibling `<topic>.dlq` for records that fail validation or processing.

## Rules worth remembering

- **Timestamps** are UTC, `YYYY-MM-DDTHH:MM:SSZ`, with no offsets or fractions (except `ingested_at`). Canonical
  form matters because the timestamp is hashed into `event_id`.
- **`event_id`** = UUIDv5(namespace `4402d35f-92c6-4368-bf94-8f364945a3e4`,
  `"{source}|{event_type}|{series}|{region}|{valid_from}|{issued_at or ''}"`). Every service tests its
  implementation against the golden examples.
- **Settlement period** is derived from `valid_from` in `Europe/London`: 1–48 normally, 1–46 or 1–50 on clock-change
  days. `demand-indo-clock-change-sp50.json` is the case to remember.
- **Evolving a schema:** a change that every existing event still satisfies (adding an optional field,
  relaxing a constraint) is a minor bump (`1.x.0`); anything else needs `v2/`.

## Changelog

| Version | Change |
|---|---|
| 1.1.0 | Generation mix values are net: `imports` (interconnector export) and `storage` (pumping) may be negative. Found in live Elexon FUELHH data. Other fuels stay non-negative. |
| 1.0.0 | Initial contract. |

## Validating

```bash
uv run schemas/tools/validate_examples.py
```
