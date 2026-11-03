# GridPulse Helm charts

Two charts, installed in one namespace (ADR-0015):
- [gridpulse/](gridpulse): GridPulse's own services, from the same images as Compose. Only the configuration differs.
- [gridpulse-dev/](gridpulse-dev): what those services need around them, for development and CI. TimescaleDB and
  Redpanda as StatefulSets with persistent volumes, MLflow, and the observability stack.

## The whole system on kind

```bash
bash deploy/kind/up.sh                  # build, create the cluster, load the images, install both charts
SKIP_BUILD=1 bash deploy/kind/up.sh     # the same with the images Compose has already built
kind delete cluster --name gridpulse    # remove it all
```

The script needs docker, kind, kubectl, helm and a `.env`, and is safe to re-run: it upgrades both releases.
`GRIDPULSE_SET` passes extra `--set` values to the `gridpulse` chart. The `kind` CI workflow runs it with
`GRIDPULSE_SET=ingestionApp.enabled=false`, then the end-to-end test with `E2E_PLATFORM=kubernetes`. It needs
about 5 GB of memory. In a Codespace, stop Compose first (`docker compose stop` keeps its data).

## The gridpulse chart

| Workload | Kind | Notes |
|---|---|---|
| `ingestion-app` | Deployment, 1 replica, `Recreate` | Two would poll every source twice. `ingestionApp.enabled=false` leaves it out, as CI does |
| `timescale-sink` | Deployment | Replicas share the topics' partitions through the consumer group |
| `forecasting-api` | Deployment + Service :8000 | |
| `scheduler-api` | Deployment + Service :8080 | |
| `scheduler-dashboard` | Deployment + Service :8080 | |
| `db-migrate`, `db-users` | Jobs, Helm hooks | Before every install and upgrade, in that order |
| `forecast-job` | CronJob, every 6 hours | |
| `train-job` | CronJob, Mondays | |

TimescaleDB, Kafka (Redpanda) and MLflow are **not** in the chart: their addresses are values (`database.*`,
`kafka.bootstrapServers`, `mlflow.trackingUri`), as a managed database or Kafka would be in production. The
`gridpulse-dev` chart provides them for development.

### Install on its own

```bash
bash deploy/helm/create-secrets.sh gridpulse         # the Secret, from .env
helm install gridpulse deploy/helm/gridpulse -n gridpulse --wait --set image.tag=<tag>
```

`image.tag` is required: every image in a release comes from one build. `image.registry` prefixes them (empty for
images loaded into kind with `kind load docker-image`).

### How it's put together

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

## The gridpulse-dev chart

| Component | Kind | Notes |
|---|---|---|
| `timescaledb` | StatefulSet, 2 Gi volume | Runs as its own `postgres` user (70); `fsGroup` makes the volume writable |
| `redpanda` | StatefulSet, 2 Gi volume | Dev-container mode, advertised as `redpanda:9092` |
| `kafka-init` | Job, `post-install`/`post-upgrade` hook | Waits for the broker, then creates the topics and registers the schemas (the `gridpulse-kafka-init` image) |
| `mlflow` | Deployment, 1 Gi artifact volume | An init container waits for its login, which the `gridpulse` chart's `db-users` hook creates |
| `otel-collector`, `prometheus`, `tempo`, `grafana` | Deployments | Configured from `observability/`, passed in with `--set-file`; telemetry on emptyDir |

- **Plain Service names.** Services are named as the `gridpulse` chart's defaults and the observability configs
  expect (`timescaledb`, `redpanda`, `otel-collector`, ...), so it's one install per namespace.
- **Restricted too.** The same Pod Security settings as the `gridpulse` chart. Nothing starts as root, the databases
  included.
- **Not for production.** One replica of everything, no backups, no TLS. Production would use managed services or
  operators behind the same `gridpulse` chart values (ADR-0015).
