# Load test: the scheduling API on Kubernetes

Step 4.7. How much load `POST /v1/schedule` takes, and how its HorizontalPodAutoscaler (HPA) responds. Measured on
2026-11-04.

## Setup

- **Where.** The whole system on kind (`deploy/kind/up.sh`) in a GitHub Codespace: **one node with 2 CPUs and 8 GB**.
  Kubernetes' own components request about 950m of those 2 CPUs. The ingestion app was off, and the end-to-end test
  had planted a day of forecasts, so every request finds a window.
- **Load.** [loadtest/k6/schedule.js](../../loadtest/k6/schedule.js) with k6 2.3.0, run inside the cluster
  ([run-in-cluster.sh](../../loadtest/k6/run-in-cluster.sh)) against the Service. Through `kubectl port-forward` it
  would have measured the tunnel instead.
  - It sends four kinds of job (30 minutes to 3 hours, either forecast source) with deadlines 12 to 20 hours away.
  - **An open model:** requests arrive at a fixed rate whether or not responses keep up. With a closed model, a
    slower server gets fewer requests and its latency looks better than it is (coordinated omission).
- **Thresholds,** so a run passes or fails: p95 under 100 ms (about 6 times what's measured below), under 0.1%
  failed requests, and every 200 holding a usable answer.

## Results

| Run | CPU request | Requests | Failed | p50 | p95 | p99 | Pods |
|---|---|---|---|---|---|---|---|
| 20/s for 3 min | 50m | 3,914 | 0 | 5.2 ms | 16.9 ms | 37.7 ms | 1 → 3 (see below) |
| 100/s for 3 min | 50m | 19,514 | 0 | 5.0 ms | 16.9 ms | 55.9 ms | 3 (the maximum here) |
| 20/s for 3 min, 60 s scale-up window | 50m | 3,914 | 0 | 5.1 ms | 12.5 ms | 20.5 ms | 1 |
| **50/s for 4 min, as shipped** | **100m** | **12,764** | **0** | **5.6 ms** | **24.3 ms** | **107 ms** | **1 → 2 after 75 s** |

- **A schedule request costs about 1.8 ms of CPU.** One pod used about 37m at 20/s; three used about 190m at 100/s.
  So at its 100m request and the 70% target, one pod takes about 38 requests a second before the HPA adds another.
- **Latency stays flat with load.** p95 was 12–24 ms from 20/s to 100/s, and nothing failed. The p99 of the shipped
  run (107 ms), and the maxima of a few hundred ms, come from a new pod's first requests (warm-up, below).

## What the runs taught (and changed)

1. **The CPU request has to match real use.** An HPA's percentage is of the request.
   - At first the API requested 50m, but an idle pod uses 10–25m (telemetry export, GC), so it looked 30–50% busy
     doing nothing. Start-up alone scaled it to four pods, which then filled the node so that k6 couldn't be
     scheduled.
   - The request is now 100m. One pod idles at about 5%.
2. **Warm-up isn't load.**
   - A new .NET pod's first requests use 2 to 3 times its request for about 30 seconds while the runtime compiles
     the hot paths: 302% of 100m in the shipped run.
   - By default the HPA scales up at once, and every new pod then spikes the same way. That's how 20/s reached three
     pods in the first run.
   - The HPA now waits 60 seconds (`behavior.scaleUp.stabilizationWindowSeconds`). In the shipped run it ignored the
     spike, then added one pod for the sustained load (about 90% of the request) and settled at about 60%.
3. **No CPU limits is what kept latency flat at 100/s.**
   - The three pods ran at 120% of their requests: they used idle CPU on the node, which a request doesn't prevent
     and a limit would.
   - With a 50m limit they'd have been throttled at the moment they were busiest.
4. **A Service balances connections, not requests.**
   - At 50/s the second pod got much less traffic than the first (about 20m of CPU against 100m). k6, like most
     HTTP/1.1 clients, keeps its connections open, and they stay on the pod they first reached.
   - New pods mostly serve new connections. Clients that open connections often (or an L7 proxy or service mesh
     balancing per request) spread load better.
5. **`helm upgrade --reuse-values` keeps the old release's values, not just the overrides.** A re-run of the 20/s
   test still had the 50m request after the chart's default changed. `up.sh` passes every value explicitly instead.

## Limits of these numbers

- **One small, shared node.** k6, the API, its database and the rest of the stack all share 2 CPUs, so this
  measures the API under contention. That's not its ceiling. The HPA here stops at 3 pods (a fourth wouldn't fit);
  the chart allows 4.
- **The forecasts are served from TimescaleDB with no cache,** so the database's CPU sits on the same node. Under
  more load, the next thing to measure is where the time goes: the traces in Tempo show it per request.
- **CI runs a shorter version** (50/s for 1 minute, p95 under 200 ms) after the end-to-end test in the `kind`
  workflow. Its threshold is looser, because shared runners vary: it's there to catch a regression (errors, or
  latency several times worse), not to benchmark.

## Reproduce

```bash
bash deploy/kind/up.sh                                    # with GRIDPULSE_SET=ingestionApp.enabled=false
E2E_PLATFORM=kubernetes python3 tests/e2e/end_to_end.py   # plants the forecasts (ports forwarded as in CI)
RATE=50 DURATION=4m bash loadtest/k6/run-in-cluster.sh
kubectl -n gridpulse get hpa -w                           # in another terminal
```
