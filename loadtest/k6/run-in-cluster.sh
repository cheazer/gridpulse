#!/usr/bin/env bash
# Run the k6 load test (schedule.js) as a Job inside the cluster, calling the scheduling API's Service directly:
# through `kubectl port-forward` it would measure the tunnel, not the API.
#   bash loadtest/k6/run-in-cluster.sh                       (after deploy/kind/up.sh and planted data)
#   RATE=50 DURATION=3m bash loadtest/k6/run-in-cluster.sh
# Exits non-zero if a threshold fails (k6 exits 99), so CI can run it as a check.
set -euo pipefail

NAMESPACE=${NAMESPACE:-gridpulse}
K6_IMAGE=${K6_IMAGE:-grafana/k6:2.3.0}
RATE=${RATE:-20}
DURATION=${DURATION:-2m}
P95_MS=${P95_MS:-100}
script_dir=$(cd "$(dirname "$0")" && pwd)

k() { kubectl --namespace "$NAMESPACE" "$@"; }

k delete job k6 --ignore-not-found --wait >/dev/null
k create configmap k6-script --from-file=schedule.js="$script_dir/schedule.js" --dry-run=client -o yaml | k apply -f - >/dev/null

# The namespace enforces the "restricted" Pod Security Standard, so the Job sets what it requires. The k6 image's own
# user is 12345.
k apply -f - >/dev/null <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: k6
  labels: { app.kubernetes.io/name: k6 }
spec:
  backoffLimit: 0
  template:
    metadata:
      labels: { app.kubernetes.io/name: k6 }
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 12345
        runAsGroup: 12345
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: k6
          image: $K6_IMAGE
          args: ["run", "--quiet", "/scripts/schedule.js"]
          env:
            - { name: RATE, value: "$RATE" }
            - { name: DURATION, value: "$DURATION" }
            - { name: P95_MS, value: "$P95_MS" }
          resources:
            # Small enough to fit beside everything on a 2-CPU node; k6 still uses whatever CPU is free.
            requests: { cpu: 100m, memory: 128Mi }
            limits: { memory: 512Mi }
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: { drop: ["ALL"] }
          volumeMounts:
            - { name: script, mountPath: /scripts }
            - { name: tmp, mountPath: /tmp }
      volumes:
        - { name: script, configMap: { name: k6-script } }
        - { name: tmp, emptyDir: {} }
EOF

echo "k6: $RATE requests/s for $DURATION after a 30s ramp, p95 threshold ${P95_MS} ms"
k wait --for=condition=ready pod -l app.kubernetes.io/name=k6 --timeout=120s >/dev/null
k logs -f job/k6
# The Job's outcome is k6's exit code: 0 if every threshold passed.
if k wait --for=condition=complete job/k6 --timeout=30s >/dev/null 2>&1; then
  echo "k6: all thresholds passed"
else
  echo "k6: a threshold failed" >&2
  exit 1
fi
