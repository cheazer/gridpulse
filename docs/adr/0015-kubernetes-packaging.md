# 0015. Kubernetes packaging: two Helm charts, init images, and the databases in the cluster for development

- **Status:** Accepted
- **Date:** 2026-11-03

## Context

Phase 4 runs GridPulse on Kubernetes as well as Compose, and CI has to prove it (step 4.5). That raised these questions:
- how to package the services;
- how schema migrations and Kafka set-up run before the apps;
- where the stateful pieces (TimescaleDB, Redpanda, MLflow, the observability stack) live.

## Options considered

1. **Packaging.** Plain manifests, Kustomize, or **Helm**.
   - Helm's templating covers the differences between environments (image registry and tag, addresses, sizes) and
     has hooks for ordered steps.
   - Its cost is template syntax.
2. **Set-up steps.** Migrations, logins, topics and schemas could run as:
   - init containers in each app;
   - **Helm hook Jobs**;
   - or files mounted into stock images from ConfigMaps.

   Helm can only read files inside a chart's own folder, and `db/migrations` and `schemas/` live elsewhere. So the
   choice was between copying those files into the chart, or **building them into images**.
3. **The stateful pieces.**
   - Vendor charts or operators (Bitnami, the Redpanda and Timescale operators, kube-prometheus-stack).
   - **Small StatefulSets and Deployments of our own.**
   - Or nothing in the cluster (always external).

## Decision

The options in bold. In detail:

**Two charts, in one namespace.**
- [`gridpulse`](../../deploy/helm/gridpulse) holds GridPulse's own services. TimescaleDB, Kafka and MLflow are
  addresses in its values, as managed services would be in production.
- [`gridpulse-dev`](../../deploy/helm/gridpulse-dev) provides them for development and CI. TimescaleDB and Redpanda
  are single-replica StatefulSets with persistent volumes; MLflow, the collector, Prometheus, Tempo and Grafana are
  Deployments.
- They're separate releases because one release can't contain both: the app chart's migration hook runs before the
  release's other resources, so it would run before its own database existed.

**Set-up is in images, run as Jobs.**
- `gridpulse-db` holds Flyway, the migrations and the logins script. `gridpulse-kafka-init` holds `rpk`, the topic
  script and the schemas.
- Compose runs the same images, so both platforms set up identically, and the schema version ships with the release
  that needs it.
- In the app chart they're `pre-install`/`pre-upgrade` hooks: Helm waits for them, and a failure stops the release.
  Kafka set-up is a `post-install`/`post-upgrade` hook of the dev chart, after its broker exists.

**Ordering by hooks and probes, not start-up order.**
- Liveness only checks the process. Readiness includes the dependencies.
- A pod that waits on another component's set-up, such as MLflow waiting for its login, waits in an init container
  rather than crash-looping.

**Locked down by default.**
- Every pod in both charts passes the "restricted" Pod Security Standard: a numeric non-root user, no privilege
  escalation, all capabilities dropped, and the runtime's default seccomp profile.
- Every pod also has a read-only root filesystem and no service-account token.
- `deploy/kind/up.sh` enforces the standard on the namespace.
- Volumes are made writable with `fsGroup`, so even the databases never start as root.

**No passwords in Helm.** `create-secrets.sh` makes one Secret from `.env`, and each pod reads only the keys it
needs.

**One copy of each config.** The observability configs stay in `observability/` and reach the dev chart through
`--set-file`. Compose mounts the same files, and Services use the names those files expect.

## Consequences

- One script, [`deploy/kind/up.sh`](../../deploy/kind/up.sh), brings up the whole system on a laptop or a CI runner
  from the same images as Compose.
- **The dev chart is not for production.** It runs one replica of each database, with no backups, no TLS and no
  failover, and keeps telemetry on emptyDir volumes. Production would use:
  - a managed PostgreSQL/TimescaleDB, or an operator such as CloudNativePG;
  - managed Kafka, or the Redpanda operator;
  - a hosted or operator-run Prometheus, Tempo and Grafana.

  The app chart wouldn't change: only its values would.
- Two more images to build and version (`gridpulse-db`, `gridpulse-kafka-init`). They're small, and versioned with the
  apps.
- Running everything takes about 5 GB of memory, which is more than the Codespace has free alongside Compose. Use
  one or the other there.
