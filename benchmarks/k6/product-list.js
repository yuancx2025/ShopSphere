// Legacy GET /product/: returns the whole catalog, so keep the rate and the
// dataset modest. Records response size, which Sprint 1 compares against the
// paginated endpoint.
import http from 'k6/http';
import {
  BASE_URL, REQUEST_TIMEOUT, arrivalScenario, gateThresholds, recordOutcome,
  requireEnvNumber, summaryTrendStats, writeSummary,
} from './lib/common.js';

const ROUTE = 'product_list';
const TOTAL_PRODUCTS = requireEnvNumber('TOTAL_PRODUCTS');

export const options = {
  scenarios: { product_list: arrivalScenario() },
  thresholds: gateThresholds(ROUTE),
  summaryTrendStats,
};

export default function () {
  const res = http.get(`${BASE_URL}/product/`, {
    tags: { route: ROUTE, name: 'GET /product/' },
    timeout: REQUEST_TIMEOUT,
  });
  recordOutcome(res, 'list', (r) => {
    const body = r.json();
    return Array.isArray(body) && body.length === TOTAL_PRODUCTS && typeof body[0].id === 'number';
  });
}

export function handleSummary(data) {
  return writeSummary(data, ROUTE);
}
