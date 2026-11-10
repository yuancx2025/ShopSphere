// Admin catalog writes for the Sprint 5 event pipeline: every arrival-rate iteration commits one outbox row,
// which becomes one ProductChanged event.
//
// Even iterations re-save a product's own fields (POST /product/update/{id}: an UPDATED event that bumps
// search_version, so the search document's version must follow). Odd iterations add one unit of stock
// (POST /product/{id}/stock-adjustments: a STOCK event). The product for each iteration comes from a hash of the
// iteration number and TRACE_SEED over /data.json, which bench.py writes from MySQL before the run.
// ADMIN_TOKEN is signed in once by bench.py, so no bcrypt sign-in lands in the measured window.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import {
  BASE_URL, REQUEST_TIMEOUT, arrivalScenario, gateThresholds, recordOutcome,
  summaryTrendStats, unitHash, writeSummary,
} from './lib/common.js';

const ROUTE = 'catalog_edit';
const TRACE_SEED = Number(__ENV.TRACE_SEED || 1);
const products = new SharedArray('products', () => JSON.parse(open('/data.json')));

if (!__ENV.ADMIN_TOKEN) {
  throw new Error('ADMIN_TOKEN must be set (bench.py signs the admin in before the run)');
}

const params = (name) => ({
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${__ENV.ADMIN_TOKEN}` },
  tags: { route: ROUTE, name },
  timeout: REQUEST_TIMEOUT,
});

export const options = {
  scenarios: { catalog_edit: arrivalScenario() },
  thresholds: gateThresholds(ROUTE),
  summaryTrendStats,
};

export default function () {
  const n = exec.scenario.iterationInTest;
  const p = products[Math.floor(unitHash(n, TRACE_SEED) * products.length)];
  if (n % 2 === 0) {
    const body = JSON.stringify({
      name: p.name, imageURL: p.imageURL, price: p.price, description: p.description, categoryId: p.categoryId,
    });
    const res = http.post(`${BASE_URL}/product/update/${p.id}`, body, params('POST /product/update/{id}'));
    recordOutcome(res, 'update', (r) => r.json().success === true);
  } else {
    const res = http.post(`${BASE_URL}/product/${p.id}/stock-adjustments`, JSON.stringify({ delta: 1 }),
      params('POST /product/{id}/stock-adjustments'));
    recordOutcome(res, 'stock', (r) => r.json().productId === p.id);
  }
}

export function handleSummary(data) {
  return writeSummary(data, ROUTE);
}
