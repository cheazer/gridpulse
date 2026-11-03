#!/usr/bin/env bash
# The whole of GridPulse on a local Kubernetes cluster (kind), from this checkout:
#   bash deploy/kind/up.sh            (from the repo root; needs docker, kind, kubectl, helm and a .env)
#   SKIP_BUILD=1 bash deploy/kind/up.sh   (use the images Compose has already built)
#   GRIDPULSE_SET=ingestionApp.enabled=false bash deploy/kind/up.sh   (extra --set values for the gridpulse chart)
# Builds the images, creates the cluster, loads the images into it, and installs the two charts into one namespace
# that enforces the "restricted" Pod Security Standard:
#   gridpulse-dev   TimescaleDB, Redpanda, MLflow, and the observability stack;
#   gridpulse       GridPulse's own services, whose hooks migrate the database and create the logins first.
# Safe to re-run: it reuses the cluster and upgrades both releases. CI (step 4.5) runs it too.
# Remove everything with:  kind delete cluster --name gridpulse
set -euo pipefail

CLUSTER=${CLUSTER:-gridpulse}
NAMESPACE=${NAMESPACE:-gridpulse}
TAG=${TAG:-dev}
# Compose builds every image; these are the services that build them, and the images they produce.
SERVICES=(ingestion-app timescale-sink forecasting-api scheduler-api scheduler-dashboard migrate redpanda-init mlflow)
IMAGES=(ingestion-app timescale-sink forecasting-api scheduler-api scheduler-dashboard db kafka-init mlflow)

step() { printf '\n--- %s\n' "$*"; }

if [[ ${SKIP_BUILD:-0} == 1 ]]; then
  step "using the images already built (SKIP_BUILD=1)"
else
  step "build images"
  # One at a time: building them in parallel runs a small machine (a Codespace, a CI runner) out of memory.
  for service in "${SERVICES[@]}"; do
    docker compose build "$service"
  done
fi

step "cluster"
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --wait 120s

step "load images"
for image in "${IMAGES[@]}"; do
  docker tag "gridpulse-$image:latest" "gridpulse-$image:$TAG"
  kind load docker-image --name "$CLUSTER" "gridpulse-$image:$TAG"
done

step "namespace and secret"
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl label namespace "$NAMESPACE" pod-security.kubernetes.io/enforce=restricted --overwrite
bash deploy/helm/create-secrets.sh "$NAMESPACE"

step "infrastructure (gridpulse-dev)"
# No --wait: MLflow only becomes ready once the next release has created its login. Helm still waits for the
# kafka-init hook, which waits for the broker.
helm upgrade --install gridpulse-dev deploy/helm/gridpulse-dev --namespace "$NAMESPACE" \
  --set image.tag="$TAG" \
  --set-file observability.config.collector=observability/otel-collector/config.yaml \
  --set-file observability.config.prometheus=observability/prometheus/prometheus.yml \
  --set-file observability.config.tempo=observability/tempo/tempo.yaml \
  --set-file observability.config.grafanaDatasources=observability/grafana/provisioning/datasources/datasources.yaml \
  --set-file observability.config.grafanaDashboardProviders=observability/grafana/provisioning/dashboards/dashboards.yaml \
  --set-file observability.dashboards.gridpulseOverview=observability/grafana/dashboards/gridpulse-overview.json \
  --timeout 10m
kubectl --namespace "$NAMESPACE" rollout status statefulset/timescaledb --timeout=5m

step "GridPulse"
helm upgrade --install gridpulse deploy/helm/gridpulse --namespace "$NAMESPACE" \
  --set image.tag="$TAG" \
  --set telemetry.otlpEndpoint=http://otel-collector:4318 \
  --set telemetry.environment=kind \
  ${GRIDPULSE_SET:+--set "$GRIDPULSE_SET"} \
  --wait --timeout 10m

step "waiting for everything"
for workload in deployment/mlflow deployment/otel-collector deployment/prometheus deployment/tempo deployment/grafana; do
  kubectl --namespace "$NAMESPACE" rollout status "$workload" --timeout=5m
done
kubectl --namespace "$NAMESPACE" get pods

cat <<EOF

GridPulse is running in namespace $NAMESPACE. To open something, forward its port, e.g.:
  kubectl -n $NAMESPACE port-forward svc/gridpulse-scheduler-dashboard 8200:8080   # http://localhost:8200
  kubectl -n $NAMESPACE port-forward svc/grafana 3000:3000                          # http://localhost:3000
EOF
