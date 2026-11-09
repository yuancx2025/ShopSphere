// Checkout contention burst (Sprint 1, PR 03): USERS shoppers, one per VU, each POST once to
// /order/create-checkout-session for one unit of the hot SKU at the same moment. The backend runs the
// fake payment gateway (bench profile), so no real payment is created.
//
// Expected outcomes: 200 with a session URL (reserved) or 409 (not enough stock). Anything else is an
// unexpected failure. bench.py checks the database invariants after the run: accepted reservations equal
// the initial stock, and stock + held units still add up to it.
//
// Tokens come from /tokens.json, written by bench.py before the run, so bcrypt sign-ins never run inside
// the measured window.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import {
  BASE_URL, REQUEST_TIMEOUT, recordOutcome, requireEnvNumber, responseBytes, successfulRequests,
  summaryTrendStats, unexpectedFailures, writeSummary,
} from './lib/common.js';

const ROUTE = 'checkout_create';
const USERS = requireEnvNumber('USERS');
const KEY_PREFIX = __ENV.KEY_PREFIX || 'burst';
const TOKENS = JSON.parse(open('/tokens.json'));

const accepted = new Counter('checkout_accepted');
const rejected = new Counter('checkout_rejected');

if (TOKENS.length < USERS) {
  throw new Error(`need ${USERS} tokens, /tokens.json has ${TOKENS.length}`);
}

export const options = {
  scenarios: {
    checkout_burst: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    [`http_reqs{route:${ROUTE}}`]: ['count>=0'],
    [`http_req_duration{route:${ROUTE}}`]: ['max>=0'],
    unexpected_failures: ['rate<0.01'],
  },
  summaryTrendStats,
};

export default function () {
  const shopper = exec.vu.idInTest - 1;
  const res = http.post(`${BASE_URL}/order/create-checkout-session`, null, {
    headers: {
      Authorization: `Bearer ${TOKENS[shopper]}`,
      'Idempotency-Key': `${KEY_PREFIX}-${shopper}`,
    },
    tags: { route: ROUTE, name: 'POST /order/create-checkout-session' },
    timeout: REQUEST_TIMEOUT,
    // 409 is an expected outcome here, not an HTTP failure.
    responseCallback: http.expectedStatuses(200, 409),
  });
  if (res.status === 409) {
    // An explicit stock rejection is a correct outcome, so it counts as a successful request.
    check(res, { 'checkout: 409 is a stock rejection': (r) => /stock/i.test(r.body || '') });
    rejected.add(1);
    successfulRequests.add(1);
    unexpectedFailures.add(false);
    responseBytes.add(res.body ? res.body.length : 0);
    return;
  }
  const ok = recordOutcome(res, 'checkout', (r) => typeof r.json('url') === 'string');
  if (ok) accepted.add(1);
}

export function handleSummary(data) {
  return writeSummary(data, ROUTE);
}
