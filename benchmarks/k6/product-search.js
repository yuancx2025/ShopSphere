// GET /product/search: one keyword search per arrival-rate iteration (Sprint 3, PR 08).
//
// ENGINE=mysql|elasticsearch  SIZE=20
// Queries are the 480 real WANDS search queries, mounted by bench.py at /queries.json. The query for each
// iteration comes from a hash of the global iteration number and TRACE_SEED, so both engines replay exactly the
// same trace. A request succeeds when it returns 200, answers from the requested engine, and returns a well-formed
// page (at most SIZE items, each with an id and a price). An empty result is a valid answer: some WANDS queries
// have no match in a given engine.
import http from 'k6/http';
import exec from 'k6/execution';
import {
  BASE_URL, REQUEST_TIMEOUT, arrivalScenario, gateThresholds, recordOutcome,
  summaryTrendStats, unitHash, writeSummary,
} from './lib/common.js';

const ROUTE = 'product_search';
const ENGINE = __ENV.ENGINE || 'elasticsearch';
const SIZE = Number(__ENV.SIZE || 20);
const TRACE_SEED = Number(__ENV.TRACE_SEED || 1);
const QUERIES = JSON.parse(open('/queries.json'));

if (!['mysql', 'elasticsearch'].includes(ENGINE)) {
  throw new Error(`ENGINE must be mysql or elasticsearch, got ${ENGINE}`);
}

export const options = {
  scenarios: { product_search: arrivalScenario() },
  thresholds: gateThresholds(ROUTE),
  summaryTrendStats,
  tags: { engine: ENGINE },
};

export default function () {
  const q = QUERIES[Math.floor(unitHash(exec.scenario.iterationInTest, TRACE_SEED) * QUERIES.length)];
  const res = http.get(
    `${BASE_URL}/product/search?q=${encodeURIComponent(q)}&engine=${ENGINE}&size=${SIZE}`,
    { tags: { route: ROUTE, name: 'GET /product/search' }, timeout: REQUEST_TIMEOUT },
  );
  recordOutcome(res, 'search', (r) => {
    const body = r.json();
    return body.engine === ENGINE
      && Array.isArray(body.items)
      && body.items.length <= SIZE
      && body.items.every((item) => Number.isInteger(item.id) && typeof item.price === 'number');
  });
}

export function handleSummary(data) {
  return writeSummary(data, ROUTE);
}
