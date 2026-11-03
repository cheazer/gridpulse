# 0014. Observability: OpenTelemetry everywhere, one collector, Prometheus, Tempo and Grafana

- **Status:** Accepted
- **Date:** 2026-11-03

## Context

GridPulse runs services in three languages:
- Java: ingestion and the sink;
- Python: the forecasting API and its jobs;
- .NET: the scheduler and the dashboard.

Phase 4 has to answer these questions without reading logs:
- Is data still arriving?
- Are forecasts fresh?
- How slow are requests, and where?

They should be answered the same way for every service, so that one dashboard and one set of alerts (step 4.6) cover
everything, and so that a request can be followed across service boundaries.

## Options considered

1. **Instrumentation.** Each language's Prometheus client, or **OpenTelemetry**.
   - OpenTelemetry is the vendor-neutral standard, covers traces and metrics with one SDK per language, and
     propagates W3C `traceparent` across HTTP calls. Its semantic conventions give the same metric names in every
     language (`http.server.request.duration`, in seconds).
   - Per language it means:
     - .NET: the OTel SDK;
     - Python: the OTel SDK;
     - Java: **Spring Boot 4's own support**, Micrometer Observation bridged to OTel, chosen over the OTel Java agent.
       It's idiomatic Spring with no bytecode agent. The cost: Spring Kafka can't trace the sink's batch listener,
       and there are no per-statement JDBC spans.
2. **Collecting.** Prometheus scraping every service, or **every service pushing OTLP to one collector**, which
   Prometheus scrapes and which forwards traces to Tempo.
   - Pushing keeps one pipeline and one address for both signals, and suits short-lived jobs that are gone before a
     scrape.
   - The cost is one more hop, which can fail. Its own metrics are scraped too, so that's visible.
3. **Protocol.** OTLP over gRPC (port 4317), or **OTLP over HTTP (port 4318)**.
   - HTTP is what Python and Spring default to and what .NET supports with one variable. One protocol means one
     endpoint value for every service.
4. **Backends.**
   - **Prometheus** for metrics: the standard, and PromQL for alerts.
   - **Tempo** for traces: no index to run, and Grafana links traces to metrics.
   - **Grafana** for both.
   - Loki (logs) is left out for now: logs stay on stdout, which `docker compose logs` and `kubectl logs` read.
     Adding Loki later is one more exporter in the collector.

## Decision

The options in bold.

**One contract.** Every service reads `OTEL_EXPORTER_OTLP_ENDPOINT`.
- Compose sets it to `http://otel-collector:4318`, and Helm will set it the same way.
- Unset, a service exports nothing, so tests and local runs need no collector.
- Java has a small `telemetry` module that maps the variable to Spring Boot's settings, since Spring doesn't read it
  itself.

**One unit.** Durations are in seconds everywhere. Python opts into the stable HTTP conventions, and Spring's OTLP
registry is set to seconds.

**Alerts go on symptoms, measured where they can't go missing:**
- `gridpulse.forecast.age`: forecast freshness, read from the database by the always-on API;
- `gridpulse.sink.write.age`: time since each event type was last written.

These catch a crashed job, a stopped poller, an unreachable broker and a stuck sink alike. Metrics sent from inside a
job couldn't show a job that never ran.

**Small, labelled cardinality.** Metric tags are kept to a handful of values (source, outcome, event type, task),
because every combination is a separate time series.

**Configuration as code.**
- The collector, Prometheus, Tempo and Grafana configs live in `observability/`.
- Grafana's data sources and dashboards are provisioned from files, and the UI can't overwrite them.

## Consequences

- One dashboard and one alert syntax cover every service. A slow schedule request can be followed into the
  forecasting API and down to its query.
- The collector is a dependency of observability, not of the product. If it's down, services keep working, and only
  telemetry is lost.
- **Telemetry the Java choice doesn't give:**
  - a poll and the batch that stores it are separate traces;
  - there are no SQL spans in the Java services.

  If either becomes a problem, switching those two apps to the OTel Java agent changes no application code.
- Local storage only: Tempo keeps three days of traces on a volume, and Prometheus fifteen days. In Kubernetes these
  are single pods with persistent volumes. Production would use object storage, or a managed service, behind the
  same collector.
