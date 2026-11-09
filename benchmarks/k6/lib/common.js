// Shared settings for ShopSphere k6 scenarios. Every knob comes from an env var so
// benchmarks/bench.py can record the exact values in the run manifest.
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const BASE_URL = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
export const RATE = Number(__ENV.RATE || 40);
export const DURATION = __ENV.DURATION || '5m';
export const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '2s';
export const GATE_P95_MS = Number(__ENV.GATE_P95_MS || 300);
export const GATE_MAX_FAILURE_RATE = Number(__ENV.GATE_MAX_FAILURE_RATE || 0.01);
// Warm-up runs share the trace but never fail the process.
export const WARMUP = __ENV.WARMUP === '1';

// Requests whose status and body both match what the scenario expects.
export const successfulRequests = new Counter('successful_requests');
// Anything else, including timeouts and wrong bodies with HTTP 200.
export const unexpectedFailures = new Rate('unexpected_failures');
// Body length in characters (equal to bytes for this ASCII JSON).
export const responseBytes = new Trend('response_bytes');

export function requireEnvNumber(name) {
  const value = Number(__ENV[name]);
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`${name} must be set to a positive number (bench.py reads it from the fixture manifest)`);
  }
  return value;
}

// Open-model load: k6 starts RATE iterations per second whether or not earlier
// ones finished, so a slow server shows up as latency and dropped iterations
// instead of quietly lowering the offered rate.
export function arrivalScenario() {
  // Little's law: in-flight = rate x latency. Pre-allocate for a 500 ms stall and allow up to 2 s.
  // VUs started mid-run take time to initialize, and iterations scheduled meanwhile are dropped,
  // so a too-small pre-allocation turns a brief server stall into generator-side drops.
  const preAllocatedVUs = Number(__ENV.PRE_VUS || Math.max(20, Math.ceil(RATE * 0.5)));
  const maxVUs = Number(__ENV.MAX_VUS || Math.max(100, Math.ceil(RATE * 2)));
  return {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs,
    maxVUs,
  };
}

// The provisional steady-state gate from the roadmap's benchmark contract.
// The always-true entries make k6 report per-route submetrics, which keeps
// setup() requests (such as sign-ins) out of the route's request count.
export function gateThresholds(route) {
  if (WARMUP) {
    return {
      [`http_req_duration{route:${route}}`]: ['max>=0'],
      [`http_reqs{route:${route}}`]: ['count>=0'],
    };
  }
  return {
    [`http_reqs{route:${route}}`]: ['count>=0'],
    [`http_req_duration{route:${route}}`]: [`p(95)<${GATE_P95_MS}`],
    unexpected_failures: [`rate<${GATE_MAX_FAILURE_RATE}`],
    dropped_iterations: ['count==0'],
  };
}

export const summaryTrendStats = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'];

// Records one request outcome. `bodyMatches` receives the response and must
// return true only when the payload is the one the scenario asked for.
export function recordOutcome(res, label, bodyMatches) {
  let matches = false;
  if (res.status === 200) {
    try {
      matches = bodyMatches(res) === true;
    } catch (err) {
      matches = false;
    }
  }
  check(res, {
    [`${label}: status 200`]: (r) => r.status === 200,
    [`${label}: expected body`]: () => matches,
  });
  const ok = res.status === 200 && matches;
  unexpectedFailures.add(!ok);
  if (ok) {
    successfulRequests.add(1);
  }
  responseBytes.add(res.body ? res.body.length : 0);
  return ok;
}

// Deterministic per-iteration randomness: the same global iteration number always
// maps to the same value, however iterations are spread across VUs.
export function unitHash(n, salt) {
  let a = (Math.imul(n, 0x9e3779b1) ^ Math.imul(salt, 0x85ebca77)) | 0;
  a = (a + 0x9e3779b9) | 0;
  let t = a ^ (a >>> 16);
  t = Math.imul(t, 0x21f0aaad);
  t ^= t >>> 15;
  t = Math.imul(t, 0x735a2d97);
  t ^= t >>> 15;
  return (t >>> 0) / 4294967296;
}

function metricValues(data, name) {
  return (data.metrics[name] && data.metrics[name].values) || {};
}

// Writes the machine-readable summary for bench.py plus a short console report.
export function writeSummary(data, route) {
  const outputs = {};
  if (__ENV.SUMMARY_FILE) {
    outputs[__ENV.SUMMARY_FILE] = JSON.stringify(data, null, 2);
  }
  const latency = metricValues(data, `http_req_duration{route:${route}}`);
  const fallback = metricValues(data, 'http_req_duration');
  const lat = Object.keys(latency).length ? latency : fallback;
  const routeReqs = metricValues(data, `http_reqs{route:${route}}`);
  const reqs = Object.keys(routeReqs).length ? routeReqs : metricValues(data, 'http_reqs');
  const ok = metricValues(data, 'successful_requests');
  const fail = metricValues(data, 'unexpected_failures');
  const dropped = metricValues(data, 'dropped_iterations');
  const fmt = (v, digits = 1) => (typeof v === 'number' ? v.toFixed(digits) : (0).toFixed(digits));
  outputs.stdout = [
    '',
    `  ${WARMUP ? 'warm-up' : 'measured'} ${route} @ ${RATE}/s for ${DURATION}`,
    `  requests ${reqs.count || 0} (${fmt(reqs.rate)}/s), successful ${ok.count || 0} (${fmt(ok.rate)}/s)`,
    `  latency ms  p50 ${fmt(lat.med)}  p95 ${fmt(lat['p(95)'])}  p99 ${fmt(lat['p(99)'])}  max ${fmt(lat.max)}`,
    `  unexpected failures ${fmt((fail.rate || 0) * 100, 2)}%  dropped iterations ${dropped.count || 0}`,
    '',
  ].join('\n');
  return outputs;
}
