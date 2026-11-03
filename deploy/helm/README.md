# GridPulse Helm chart

[gridpulse/](gridpulse) deploys GridPulse's own services to Kubernetes, from the same images as Compose. Only the
configuration differs.

| Workload | Kind | Notes |
|---|---|---|
| `ingestion-app` | Deployment, 1 replica, `Recreate` | Two would poll every source twice |
| `timescale-sink` | Deployment | Replicas share the topics' partitions through the consumer group |
| `forecasting-api` | Deployment + Service :8000 | |
| `scheduler-api` | Deployment + Service :8080 | |
| `scheduler-dashboard` | Deployment + Service :8080 | |
| `db-migrate`, `db-users` | Jobs, Helm hooks | Before every install and upgrade, in that order |
| `forecast-job` | CronJob, every 6 hours | |
| `train-job` | CronJob, Mondays | |

TimescaleDB, Kafka (Redpanda) and MLflow are **not** in the chart: their addresses are values (`database.*`,
`kafka.bootstrapServers`, `mlflow.trackingUri`), as a managed database or Kafka would be in production. Step 4.4
provides them in the cluster for development.

## Install

```bash
bash deploy/helm/create-secrets.sh gridpulse         # the Secret, from .env
helm install gridpulse deploy/helm/gridpulse -n gridpulse --wait --set image.tag=<tag>
```

`image.tag` is required: every image in a release comes from one build. `image.registry` prefixes them (empty for
images loaded into kind with `kind load docker-image`).

## How it's put together

- **No passwords in Helm.** [create-secrets.sh](create-secrets.sh) creates the `gridpulse-secrets` Secret from
  `.env`, outside the chart, so passwords never appear in values, rendered manifests or Helm's release history. Each
  workload reads only the keys it needs, through `secretKeyRef`.
- **Ordering comes from hooks and probes, not start-up order.**
  - `db-migrate` then `db-users` run as `pre-install`/`pre-upgrade` hooks, from the `gridpulse-db` image with the
    migrations baked in. Helm waits for them, and a failure stops the release, so no app starts against a schema it
    doesn't expect.
  - Apps that start before a dependency is up simply aren't ready yet: the readiness probe keeps traffic away until
    they are.
- **Probes.**
  - The startup probe gives slow starters (the JVM) time before liveness counts.
  - Liveness (`/health`, or Actuator's liveness group) only asks "is the process working?". A database outage never
    restarts a pod, because a restart can't fix it.
  - Readiness (`/ready`) includes the dependencies the service needs to answer requests.
- **The "restricted" Pod Security Standard.** The chart's pods run as a numeric non-root user, with no privilege
  escalation, all capabilities dropped and the runtime's default seccomp profile. Each also gets a read-only root
  filesystem with a small `/tmp`, and no service-account token. The test namespace enforces the standard, so a
  violation is rejected at install.
- **Resources.** Every container requests CPU and memory and has a memory limit. There are no CPU limits: they
  throttle a busy pod even when the node is idle. The JVM sizes its heap from the memory limit
  (`MaxRAMPercentage=75`).
- **Telemetry.** The same `OTEL_*` contract as Compose (ADR-0014). `telemetry.otlpEndpoint` names the collector; the
  pod and namespace are added to every span and metric's resource attributes.
