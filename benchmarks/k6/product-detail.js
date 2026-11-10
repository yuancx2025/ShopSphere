// GET /product/{productID}: one request per arrival-rate iteration.
//
// PATTERN=uniform  every catalog product is equally likely
// PATTERN=hot      HOT_TRAFFIC share of reads go to the first HOT_KEYS share of ids
//
// The product id for each iteration comes from a hash of the global iteration
// number and TRACE_SEED, so a given (seed, pattern, rate, duration) replays the
// same request sequence.
import http from 'k6/http';
import exec from 'k6/execution';
import {
  BASE_URL, REQUEST_TIMEOUT, arrivalScenario, gateThresholds, recordCacheOutcome, recordOutcome,
  requireEnvNumber, summaryTrendStats, unitHash, writeSummary,
} from './lib/common.js';

const ROUTE = 'product_detail';
const PRODUCT_COUNT = requireEnvNumber('PRODUCT_COUNT');
const PATTERN = __ENV.PATTERN || 'uniform';
const HOT_KEYS = Number(__ENV.HOT_KEYS || 0.2);
const HOT_TRAFFIC = Number(__ENV.HOT_TRAFFIC || 0.8);
const TRACE_SEED = Number(__ENV.TRACE_SEED || 1);
// Self-test for the merge gate: expect the wrong id so every check must fail.
const EXPECT_WRONG_ID = __ENV.EXPECT_WRONG_ID === '1';

if (!['uniform', 'hot'].includes(PATTERN)) {
  throw new Error(`PATTERN must be uniform or hot, got ${PATTERN}`);
}

export const options = {
  scenarios: { product_detail: arrivalScenario() },
  thresholds: gateThresholds(ROUTE),
  summaryTrendStats,
  tags: { pattern: PATTERN },
};

export function pickProductId(iteration) {
  const index = unitHash(iteration, TRACE_SEED);
  if (PATTERN === 'uniform') {
    return 1 + Math.floor(index * PRODUCT_COUNT);
  }
  const hotCount = Math.max(1, Math.floor(PRODUCT_COUNT * HOT_KEYS));
  const coldCount = PRODUCT_COUNT - hotCount;
  const useHot = coldCount === 0 || unitHash(iteration, TRACE_SEED + 7919) < HOT_TRAFFIC;
  return useHot
    ? 1 + Math.floor(index * hotCount)
    : hotCount + 1 + Math.floor(index * coldCount);
}

export default function () {
  const id = pickProductId(exec.scenario.iterationInTest);
  const expected = EXPECT_WRONG_ID ? id + 1 : id;
  const res = http.get(`${BASE_URL}/product/${id}`, {
    tags: { route: ROUTE, name: 'GET /product/{productID}' },
    timeout: REQUEST_TIMEOUT,
  });
  recordOutcome(res, 'detail', (r) => r.json('id') === expected);
  recordCacheOutcome(res);
}

export function handleSummary(data) {
  return writeSummary(data, ROUTE);
}
