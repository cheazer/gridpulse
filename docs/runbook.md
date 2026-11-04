# Runbook: GridPulse alerts

What each alert in [observability/prometheus/rules/gridpulse.yml](../observability/prometheus/rules/gridpulse.yml)
means and what to check first. Every alert links to its section here. They go on symptoms (ADR-0014): what a user of
the data or the scheduler would notice.

Commands are for Compose (`docker compose ...`); on Kubernetes use `kubectl -n gridpulse logs deploy/<name>` and
`kubectl -n gridpulse exec`. The **GridPulse overview** dashboard in Grafana shows the panels named below.

| Alert | Severity | Fires when |
|---|---|---|
| [GridPulseDataStale](#gridpulsedatastale) | critical | No events of a type written for 2 hours, for 10 minutes |
| [GridPulseForecastsStale](#gridpulseforecastsstale) | warning | A task's newest forecast over 7 hours old, for 15 minutes |
| [GridPulseEventsDeadLettered](#gridpulseeventsdeadlettered) | warning | Over 5% of a type's events dead-lettered over an hour, for 15 minutes |
| [GridPulseSinkUndecodable](#gridpulsesinkundecodable) | warning | Any record the sink couldn't decode in 15 minutes |
| [GridPulseSchedulerSlow](#gridpulseschedulerslow) | warning | p95 of `POST /v1/schedule` over 1 s, for 10 minutes |
| [GridPulseSchedulerErrors](#gridpulseschedulererrors) | critical | Over 5% of scheduling API responses are 5xx, for 10 minutes |
| [GridPulseCollectorDown](#gridpulsecollectordown) | critical | Prometheus can't scrape the collector, for 2 minutes |
| [GridPulseServiceSilent](#gridpulseservicesilent) | critical | A service seen in the last 6 hours sends nothing, for 5 minutes |

**Critical** means GridPulse is giving wrong answers, or none, now. **Warning** means it's degraded or about to be.

## GridPulseDataStale

The sink hasn't stored an event type for over two hours. Normally every type is written at least every 30 minutes,
or hourly for weather. Everything downstream (forecasts, the scheduler's actuals) goes stale with it.

Check in order:
1. **The source.** Read `docker compose logs ingestion-app`: is the source API down, rate-limiting or changed? The
   *Events published* panel shows whether anything is being published at all.
2. **Kafka and the sink.** If events are published but not written, look at *Sink consumer lag* and
   `docker compose logs timescale-sink`.
3. **The database.** A sink that can't write logs the database error and stops committing offsets, so nothing is
   lost. It catches up once the database is back.

## GridPulseForecastsStale

No forecast job has stored a forecast for the task for over 7 hours; the job runs every 6. The scheduler falls back
to NESO's forecast, so it still answers, but not with GridPulse's own forecast or its range.

Check the forecast job's last runs: on Kubernetes,
`kubectl -n gridpulse get pods -l app.kubernetes.io/name=forecast-job` and the logs of the latest one. The usual
causes are no champion in MLflow (run `train`), MLflow unreachable, or the database. In Compose, nothing schedules
the job: run `docker compose --profile jobs run --rm forecast-job`.

## GridPulseEventsDeadLettered

The ingestion app validates every event against its schema before publishing, and sends failures to the type's
`.dlq` topic instead. A few are normal: a source occasionally sends a value outside its documented range. More than
5% means the source has changed shape.

Read some with `docker compose exec redpanda rpk topic consume gridpulse.<type>.v1.dlq -n 5`, and compare them with
the schema in `schemas/events/v1/`. Fix the mapper, or the schema in a backward-compatible way (ADR-0002), then
replay the dead letters.

## GridPulseSinkUndecodable

The sink received records it couldn't decode. Everything the ingestion app publishes has passed validation, so this
is a contract break between the two, usually a schema change deployed to one side only. The records went to the
dead-letter topics, so nothing is lost.

Check that the ingestion app and the sink run images built from the same commit (the same `events-common`), then
replay the dead letters.

## GridPulseSchedulerSlow

95% of `POST /v1/schedule` requests should take under a second.

Open a slow request from the *Recent schedule requests* panel, which links to its trace in Tempo. The time is
usually in the call to the forecasting API (its own spans show its query) or in the scheduler's database query.
Retries and an open circuit breaker show as repeated or missing client spans.

## GridPulseSchedulerErrors

More than 5% of scheduling API responses are 5xx.
- **503:** forecasts are unavailable, because the database or the forecasting API is unreachable after retries.
  `docker compose logs scheduler-api` names the dependency.
- **500:** a bug. The log has the stack trace and the trace ID, and the failed span is in Tempo.

## GridPulseCollectorDown

Prometheus can't scrape the OpenTelemetry collector, so no metrics or traces are arriving and every other alert has
gone quiet. The services keep working: telemetry is lost, not requests.

Check the `otel-collector` container or pod (`docker compose ps otel-collector`, its logs, an OOM kill). Its own
memory limiter refuses data before running out of memory, and says so in its logs.

## GridPulseServiceSilent

A service that sent telemetry in the last 6 hours has sent none for the last 15 minutes. The collector keeps a series
for 10 minutes, and the alert waits 5 more. It has crashed, is stuck, or can't reach the collector. Forecast and
training jobs are left out, since they stop by design.

Check that its container or pod is running (`docker compose ps`, `kubectl -n gridpulse get pods`) and read its logs.
If it's running and healthy, check its `OTEL_EXPORTER_OTLP_ENDPOINT` and that it can reach the collector.
