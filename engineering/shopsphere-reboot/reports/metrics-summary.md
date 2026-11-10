# ShopSphere reboot · metrics summary (Sprints 0–5)

[Roadmap](../README.md) · [Sprint 1](sprint-01.md) · [Sprint 2](sprint-02.md) · [Sprint 2 cloud](sprint-02-cloud.md) · [Sprint 3](sprint-03.md) · [Sprint 5](sprint-05.md)

Compiled 2026-11-09 (Sprint 5 added 2026-11-10) from the sprint reports and run artifacts. Every number below is copied from those sources; see them for the full protocol and caveats.

## Where each sprint stands

| Sprint | Code | Measured | Report | On `main`? |
| --- | --- | --- | --- | --- |
| 0 · Benchmark foundation | done | baseline detail run only | none (results in Sprint 1 report + run `20261109T163831Z`) | yes |
| 1 · MySQL + checkout correctness | done | yes | `sprint-01.md` | yes |
| 2 · Redis catalog cache | done | yes, laptop + cloud CI | `sprint-02.md`, `sprint-02-cloud.md` | yes |
| 3 · Elasticsearch search | done | yes, laptop | `sprint-03.md` | **no** — local branch `claude/sprint-3-kickoff-d65402`, not pushed |
| 4 · Payments | **deferred** | — | — | — |
| 5 · RocketMQ + workers | done (outbox, publisher, 2 consumer groups, ledger, worker split, replay) | yes, laptop | `sprint-05.md` | **no** — local branches `claude/sprint-5-readiness-979040` + `claude/sprint-5-measurements` (built on Sprint 3), not pushed |

## Sprint 0 · Baseline

Laptop, fixture v1 (10,000 products), backend 2 CPU / 1.5 GB, MySQL 2 CPU / 1 GB, k6 on the same host. 30 s warm-up, 2 min measured, 1 trial.

| Route | Rate | p50 | p95 | p99 | Failures | Drops | SQL reads / success | Bytes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `GET /product/{id}` | 40 req/s | 2.6 ms | **7.3 ms** | 25.1 ms | 0% | 0 | 1.00 | 234 |

## Sprint 1 · Checkout correctness and queries

| Metric | Before | After |
| --- | --- | --- |
| Oversell burst (100 shoppers, 20 units, 3 bursts) | **100 accepted, stock ended 9–10** (oversold) | 20 accepted, 80 rejected, stock 0, held 20 — every burst |
| Same burst over HTTP (k6, 3 trials) | not runnable | 3/3 pass all invariants; p95 0.87–1.20 s (100 requests on one hot row) |
| Price-sorted category page, rows examined | 856 | 21 (**−97.5%**) |
| Id-sorted category page, rows examined | 241–252 | 21 (−91%) |
| Catalog list response bytes (legacy list → 20-item keyset page) | 2,355,356 | 4,740 (**−99.8%**) |
| Rows examined per list request | 10,001 | 21 (−99.8%) |
| List p50 at 2 req/s | 46.9 ms | 9.1 ms (−81%) |
| Detail p95 at 40 req/s (regression check) | 7.3 ms | 8.5 ms (noise) |

Tests: 33 backend tests (Testcontainers MySQL), including `CheckoutBurstTest`, `AdminStockTest`, `PaymentOutcomeTest`.

## Sprint 2 · Redis catalog cache

### SQL reads per successful request

| Workload | Cache off | Cache on | Change | Hit ratio | Where |
| --- | --- | --- | --- | --- | --- |
| Detail, hot, 1,000 req/s | 1.00 | 0.13 | **−87%** | 89–91% | laptop |
| Detail, hot, 1,250 req/s (60 s TTL) | 0.997 | 0.096 | **−90%** | 90.4% | cloud |
| Detail, hot, 1,250 req/s (300 s TTL) | 0.997 | 0.026 | **−97%** | 97.4% | cloud |
| Detail, hot, 500 req/s | 1.000 | 0.175 | −82.5% | 82.5% | cloud |
| First catalog page, 500 req/s | 1.00 | 0.00 | **−99.9%** | ~100% | laptop + cloud |

### Latency

| Workload | Cache off p95 | Cache on p95 | Change | Where |
| --- | --- | --- | --- | --- |
| First catalog page, 500 req/s | 16.7 ms | 4.0 ms | −76% | laptop (3/3 trials each) |
| First catalog page, 500 req/s | 0.839 ms | 0.630 ms | −25% | cloud (3/3 trials each) |
| Detail, hot, 1,250 req/s, 60 s TTL | 10.54 ms | 7.21 ms | **−32%** | cloud, 5 + 5 min, 3 runs |
| Detail, hot, 1,250 req/s, 300 s TTL | 10.54 ms | 6.01 ms | **−43%** | cloud, 5 + 5 min, 3 runs |
| Detail, hot, 500 req/s | 1.34 ms | 1.42–1.49 ms | +6–11% (worse) | cloud — p95 lands on misses |
| Detail hit-only p95, 500 req/s | — | 0.64 ms | ~2× faster than uncached read | cloud |

### Capacity (cloud, 4-vCPU runners)

| Measure | Cache off | Cache on |
| --- | --- | --- |
| Highest rate passing three 5-min trials | 1,250 req/s | 1,250 req/s |
| Gradual 1-min ladder, highest passing step | 2,250 req/s | 2,500 req/s (+11%, unconfirmed sustained) |
| p95 at 2,250 req/s (ladder) | 125.9 ms | 28.5 ms |
| Peak waiting DB connections at 2,250 req/s | 184 | 0 |

### Redis failure handling (cloud, 250 req/s)

| Scenario | p95 | Drops | Failed requests |
| --- | --- | --- | --- |
| Redis stopped | 1.15 ms | 0 | 0 |
| Redis paused (hung) | 101 ms | 0 | 0 |
| Recovery | 1.55 ms | 0 | 0 |

Bypass window cut Redis errors in a stopped-Redis laptop run from 120,002 to 183. Tests: 39 backend tests on CI (six cache tests).

## Sprint 3 · Elasticsearch search (laptop)

| Metric | MySQL FULLTEXT | Elasticsearch | Target |
| --- | --- | --- | --- |
| NDCG@10, WANDS, 192 held-out queries | 0.449 | **0.707** (+0.258, 95% CI [+0.219, +0.298]) | +0.05 |
| Judged-only NDCG@10 | 0.666 | 0.767 (+0.101) | — |
| Queries better / worse / tied | — | 149 / 25 / 18 | — |
| Search p95 at 40 req/s (42,994 products, 3/3 trials) | 53.0 ms | **19.9 ms** | < 300 ms |
| Response bytes per 20 results | 21,320 | 11,474 | — |

| Index freshness / backfill | Result | Target |
| --- | --- | --- |
| Commit-to-searchable, 50 edits | p50 0.79 s, **p95 0.91 s** | < 5 s |
| 20 s Elasticsearch outage, 50 queued edits | all searchable 1.29 s after resume, 0 lost | — |
| Backfill 10,001 / 42,994 / 100,001 docs | 2.1 s / 5.0–10.9 s / 9.6 s, counts match MySQL | — |

Tests: 46 backend tests (8 new search tests).

## Sprint 5 · RocketMQ and workers (laptop)

Full detail in [sprint-05.md](sprint-05.md). Consumers run in a separate `worker` container; lag is outbox commit → ledger + effect commit, from MySQL timestamps.

| Target | Result |
| --- | --- |
| 50 unique events/sec, p95 outbox-to-effect lag < 2 s | **p95 0.11–0.31 s** in 3 of 4 trials (cache invalidator 0.11–0.22 s); the 4th hit a host-wide stall at 2.10 s |
| Highest stable input rate | **100 events/s** (p95 0.49 s); 200/s unstable, search indexer the bottleneck |
| 0 missing / 0 duplicate effects across 10,000 IDs with crash windows | **0 / 0** across 174,954 events in 11 runs, incl. worker SIGKILL (8 redeliveries skipped), backend SIGKILL, and 19,154 forced redeliveries (all skipped) |
| Recovery < 120 s after a 30 s worker stop | **41 s** after restart (2,322-event peak backlog) |
| Dead letters + replay | 60 s Elasticsearch outage → **1,223** events in `%DLQ%search-indexer` (= reconciled missing); replayed under original IDs in 4.1 s → 0 missing, 0 stale documents |

Tests: 48 backend tests.

## Résumé-ready claims so far

- Fixed a checkout oversell race: 100 concurrent shoppers on 20 units went from 100 accepted (oversold) to exactly 20, across every burst.
- Keyset pagination cut catalog response size 99.8% (2.36 MB → 4.7 KB) and rows examined 10,001 → 21.
- Redis cache-aside cut product-detail p95 32–43% (10.5 → 6.0–7.2 ms) and MySQL reads 90–97% at 1,250 req/s on 4-vCPU CI runners (name the TTL).
- Elasticsearch improved held-out NDCG@10 from 0.45 to 0.71 over MySQL FULLTEXT on WANDS (192 queries); edits searchable in 0.9 s p95; 20 s outage recovered with no lost updates.
- RocketMQ outbox pipeline with idempotent search and cache workers: 50 events/s at 0.1–0.3 s p95 lag (100/s stable); zero missing or duplicate effects across ~175,000 events with injected crashes, forced redeliveries and a dead-letter replay.

Laptop latency numbers (Sprint 1, Sprint 2 laptop, Sprint 3, Sprint 5) used a shortened 30 s + 1 min protocol on a busy host; rerun on the full protocol before quoting them.
