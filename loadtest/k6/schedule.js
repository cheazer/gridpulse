// Load test of the scheduling API's POST /v1/schedule (step 4.7), with pass/fail thresholds, so a run is a check CI
// can fail rather than numbers to eyeball.
//
// An open model: requests arrive at a fixed rate whatever the response times, as real callers do. A closed model
// (each virtual user waits for its last response) slows down with the server and hides how slow it got
// ("coordinated omission").
//
// Needs forecasts covering the next day (the end-to-end test plants them), or every request is a correct 422.
// Run inside the cluster with loadtest/k6/run-in-cluster.sh, or anywhere k6 can reach the API:
//   k6 run -e SCHEDULER_URL=http://localhost:8100 loadtest/k6/schedule.js
// Settings (environment variables): RATE requests a second (default 20), DURATION at that rate (default 2m),
// P95_MS the latency threshold (default 100: about 6x the measured p95, docs/results/load-test.md).

import http from "k6/http";
import { check } from "k6";

const SCHEDULER_URL = __ENV.SCHEDULER_URL || "http://gridpulse-scheduler-api:8080";
const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || "2m";
const P95_MS = Number(__ENV.P95_MS || 100);

export const options = {
  scenarios: {
    schedule: {
      executor: "ramping-arrival-rate",
      startRate: 1,
      timeUnit: "1s",
      // Enough virtual users to keep the rate even if responses slow down; k6 warns if it runs out.
      preAllocatedVUs: Math.max(10, RATE),
      maxVUs: RATE * 10,
      stages: [
        { target: RATE, duration: "30s" }, // ramp up
        { target: RATE, duration: DURATION }, // steady
      ],
    },
  },
  thresholds: {
    // Latency of the requests that count, at the steady rate and the ramp alike.
    "http_req_duration{name:schedule}": [`p(95)<${P95_MS}`],
    // At most 1 in 1,000 fails (a 4xx or 5xx, or no response).
    http_req_failed: ["rate<0.001"],
    // Every 200 has a usable answer.
    checks: ["rate>0.999"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

// The kinds of job a caller schedules: shorter and longer runs, against both forecast sources.
const JOBS = [
  { durationMinutes: 30, energyKwh: 5, source: "neso" },
  { durationMinutes: 60, energyKwh: 10, source: "neso" },
  { durationMinutes: 120, energyKwh: 10, source: "gridpulse" },
  { durationMinutes: 180, energyKwh: 50, source: "gridpulse" },
];

export default function () {
  const job = JOBS[Math.floor(Math.random() * JOBS.length)];
  // A deadline 12 to 20 hours away, on the hour: inside the planted forecasts, and different windows to search.
  const deadline = new Date(Date.now() + (12 + Math.floor(Math.random() * 9)) * 3600 * 1000);
  deadline.setUTCMinutes(0, 0, 0);
  const body = JSON.stringify({ ...job, deadline: deadline.toISOString(), region: "GB" });

  const response = http.post(`${SCHEDULER_URL}/v1/schedule`, body, {
    headers: { "Content-Type": "application/json" },
    tags: { name: "schedule" },
  });
  check(response, {
    "status is 200": (r) => r.status === 200,
    "has a best window": (r) => r.status === 200 && r.json("best.start") !== null,
  });
}
