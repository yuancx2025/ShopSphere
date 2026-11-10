// Authenticated read: each shopper's cart (the seeded carts hold one unit of the hot SKU).
// The checkout itself is k6/checkout-burst.js (bench.py burst).
//
// Tokens come from /tokens.json, which bench.py writes by signing every shopper in before the
// measured run. Signing in inside k6 setup() put the bcrypt sign-ins and their user queries inside
// the SQL snapshot window and inflated "SQL reads / success" (6.0 instead of the steady-state 3.0).
import http from 'k6/http';
import exec from 'k6/execution';
import {
  BASE_URL, REQUEST_TIMEOUT, arrivalScenario, gateThresholds, recordOutcome,
  requireEnvNumber, summaryTrendStats, writeSummary,
} from './lib/common.js';

const ROUTE = 'cart_read';
const USERS = requireEnvNumber('CART_USERS');
const HOT_SKU_ID = requireEnvNumber('HOT_SKU_ID');
const TOKENS = JSON.parse(open('/tokens.json'));

export const options = {
  scenarios: { cart_read: arrivalScenario() },
  thresholds: gateThresholds(ROUTE),
  summaryTrendStats,
};

if (TOKENS.length < USERS) {
  throw new Error(`need ${USERS} tokens, /tokens.json has ${TOKENS.length}`);
}

export default function () {
  const token = TOKENS[exec.scenario.iterationInTest % USERS];
  const res = http.get(`${BASE_URL}/cart/`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { route: ROUTE, name: 'GET /cart/' },
    timeout: REQUEST_TIMEOUT,
  });
  recordOutcome(res, 'cart', (r) => {
    const items = r.json('cartItems');
    return Array.isArray(items) && items.some((item) => item.product && item.product.id === HOT_SKU_ID);
  });
}

export function handleSummary(data) {
  return writeSummary(data, ROUTE);
}
