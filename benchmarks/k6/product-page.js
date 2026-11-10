// GET /product/page: first page of one category per arrival-rate iteration (Sprint 1, PR 04).
//
// SORT=id|price_asc  SIZE=20
// The category for each iteration comes from a hash of the global iteration number and TRACE_SEED, so a
// given (seed, rate, duration) replays the same requests. The body check requires a full, correctly ordered
// page of the requested category with a next cursor, so a 200 with the wrong content fails.
import http from 'k6/http';
import exec from 'k6/execution';
import {
  BASE_URL, REQUEST_TIMEOUT, arrivalScenario, gateThresholds, recordOutcome,
  requireEnvNumber, summaryTrendStats, unitHash, writeSummary,
} from './lib/common.js';

const ROUTE = 'product_page';
const CATEGORIES = requireEnvNumber('CATEGORIES');
const SIZE = Number(__ENV.SIZE || 20);
const SORT = __ENV.SORT || 'id';
const TRACE_SEED = Number(__ENV.TRACE_SEED || 1);

if (!['id', 'price_asc'].includes(SORT)) {
  throw new Error(`SORT must be id or price_asc, got ${SORT}`);
}

export const options = {
  scenarios: { product_page: arrivalScenario() },
  thresholds: gateThresholds(ROUTE),
  summaryTrendStats,
  tags: { sort: SORT },
};

function ordered(items) {
  for (let i = 1; i < items.length; i += 1) {
    const a = items[i - 1];
    const b = items[i];
    if (SORT === 'id' && !(a.id < b.id)) return false;
    if (SORT === 'price_asc' && !(a.price < b.price || (a.price === b.price && a.id < b.id))) return false;
  }
  return true;
}

export default function () {
  const categoryId = 1 + Math.floor(unitHash(exec.scenario.iterationInTest, TRACE_SEED) * CATEGORIES);
  const res = http.get(`${BASE_URL}/product/page?categoryId=${categoryId}&sort=${SORT}&size=${SIZE}`, {
    tags: { route: ROUTE, name: 'GET /product/page' },
    timeout: REQUEST_TIMEOUT,
  });
  recordOutcome(res, 'page', (r) => {
    const body = r.json();
    return Array.isArray(body.items)
      && body.items.length === SIZE
      && body.items.every((item) => item.categoryId === categoryId)
      && ordered(body.items)
      && typeof body.nextCursor === 'string';
  });
}

export function handleSummary(data) {
  return writeSummary(data, ROUTE);
}
