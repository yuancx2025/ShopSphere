# Sprint 2 report · Redis catalog cache

[Roadmap](../README.md) · [Sprint 2 plan](../sprints/02-redis-cache.md) · [Sprint 1 report](sprint-01.md) · [Benchmarks](../../../benchmarks/README.md)

Status: implemented on `claude/sprint-2-redis-cache-e29ad1`, which is built on the unmerged Sprint 0–1 branch (`claude/sprint-zero-worksheet-review-c8c1d6` at `b25aebe`). Not yet split into the planned PRs 05 and 06 or merged.

## Headline findings

1. **The cache removes almost all catalog SQL.** Product detail fell from 1.00 to 0.09–0.15 application SQL reads per successful request on the hot workload (about −88%), with an observed hit ratio of about 90%. The first catalog page fell from 1.00 to 0.00 (−99.9%).
2. **The first catalog page is faster. Product detail is not.** At 500 req/s the page's median p95 fell from 16.7 ms to 4.0 ms (−76%), and both variants passed the gate in 3/3 trials. A primary-key detail lookup already runs at 1–5 ms p95 against MySQL, so the cache has no latency to save there, as the plan predicted.
3. **A hung Redis was the dangerous failure, not a dead one.** With Redis stopped, the client fails fast and reads fall back to MySQL at p95 4.7 ms. With Redis paused (connections open, no replies), or with a JVM pause longer than the 100 ms command timeout, every in-flight request waited for a get timeout and then a put timeout. A one-second pause therefore turned into a backlog of 200 busy Tomcat threads. A one-second bypass window after any Redis error (`RedisBypassCache`) cut that to one timeout: in the integration test, the first request takes 127 ms and later ones take 8 ms on MySQL.
4. **This laptop can't hold the zero-drop gate at 1000 req/s for either variant.** The cache-off variant passed 1000 req/s in the first ladder, then failed it in a later ladder and in 2 of 3 trials. Host contention explains this. The Mac has 4 performance cores, load averaged 9–10, an iOS Simulator process used about 95% CPU, and the stack itself asks for 8 CPUs. Both variants dropped iterations in short stalls. Young-generation GC pauses reached 0.8–1.2 s during cache-on runs and 0.3 s during cache-off runs, at similar allocation rates (about 80–140 MB/s). These runs don't establish whether the cache contributes to the longer pauses. Treat the capacity result below as one observation, not a confirmed gain.

## Design decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Stock contract | Cache the full `ProductDto`, `stock` included. Stock on the detail page and catalog cards is display-only and may lag checkout by up to the TTL. | It keeps the public JSON identical with the cache on or off (the plan's merge gate) and takes stock off the read path, which the ≥50% SQL target requires. Checkout already reserves through a guarded `UPDATE … WHERE stock >= n`, so a stale display value can cause a 409 at checkout but never an oversell. `CatalogCacheTest.checkoutReservesAgainstMySqlNotTheCachedStock` pins this. |
| What is cached | `GET /product/{id}` (key `productDetail::<id>`) and the first page of `GET /product/page` (key `catalogPage::<categoryId>:<sort>:<size>`). Pages with a cursor are not cached. | The first page takes most of the traffic. Deeper cursors spread across too many keys to hit often. All keys carry `shopsphere:catalog:v1:`, so changing a DTO's shape means bumping one constant. |
| TTL | 60 s (`catalog.cache.ttl`, `BENCH_CACHE_TTL`) | This bounds how stale displayed stock and the stale-refill race can get. At 1000 req/s the hot workload still reaches ~90% hits, close to the plan's model (89% at 1000 req/s, 60 s TTL). |
| Invalidation | `@CacheEvict` on admin edit and stock adjustment (that product plus every page), and on product create (every page). The caching interceptor runs outside the transaction (`@EnableCaching(order = HIGHEST_PRECEDENCE)`), so eviction happens after commit. | Evicting after commit avoids caching uncommitted data. It also means a cache hit never opens a transaction or borrows a Hikari connection. Spring's `transactionAware()` decorator was rejected: an eviction failure in `afterCommit` would surface as an error on an admin write that had already committed. |
| Stale-refill race | Documented, not guarded. A read that loads the old row just before an admin commit can put it back after the eviction, and that entry then lives until its TTL. | The 60 s TTL bounds it. A version guard is a follow-up if freshness requirements tighten. |
| Feature flag | `spring.cache.type=none\|redis` (`SPRING_CACHE_TYPE`, `bench.py up --cache`) | With `none`, every `@Cacheable` is a plain call and Redis is never contacted. |
| Failure handling | 100 ms command timeout, 200 ms connect timeout, Lettuce `REJECT_COMMANDS` while disconnected. A `CacheErrorHandler` turns any Redis error into a miss, counts it, and opens a 1 s bypass window (`catalog.cache.bypass-after-error`). Evictions are never bypassed. | Redis errors never fail a request, and a slow Redis costs one timeout per window rather than one per request. The Redis health check is disabled, so an outage degrades the cache but never marks the backend unhealthy. |

## What was built, mapped to the plan

### PR 05 · `feat/redis-cache`

| Plan item | Implementation |
| --- | --- |
| Spring Data Redis and cache configuration | `catalog/CatalogCacheConfig`: `RedisCacheManager` with per-cache Jackson serializers (Boot's HTTP `ObjectMapper`, so cached and uncached bodies serialize the same way), `SCAN` for clears, statistics on. |
| Catalog DTO cache | `ProductService.getProductDetail` (`@Cacheable`), `CatalogService.page` (`@Cacheable`, first pages only). |
| Invalidation after commit | `@CacheEvict` on `addProduct`, `updateProduct` and `adjustStock`. |
| Hit/miss/error counters | `cache_gets_total{result="hit\|miss"}` (Boot's Redis cache metrics), `catalog_cache_errors_total{cache,operation}`, `catalog_cache_bypassed_total{cache}`. |
| Bounded fallback | `CountingErrorHandler` plus `RedisBypassCache` (the one-second bypass window). |
| Local Redis | `redis:7.4.2-alpine` in the bench Compose file (1 CPU, 256 MB, 128 MB `allkeys-lru`, no persistence). Testcontainers Redis in `CatalogCacheTest`. |
| Merge gate tests | `CatalogCacheTest` (Testcontainers MySQL + Redis): cached body equals the database read; only first pages are cached; admin edits and stock adjustments evict that product and every page after commit; checkout rejects against MySQL while the cache still shows stock; a hung Redis falls back to MySQL, opens the bypass, and caching resumes after it closes. The 33 Sprint 0–1 tests still pass with the cache off. |

### PR 06 · `test/cache-performance`

| Plan item | Implementation |
| --- | --- |
| A/B switch | `bench.py up --cache none\|redis`. Every manifest records the backend's actual `SPRING_CACHE_TYPE` and TTL, read from inside the container. |
| Counters per trial | Hit ratio, Redis errors and bypassed reads for the route's cache, taken as before/after snapshots of the internal Prometheus endpoint around the measured window. They appear in the trial table and in `compare`. |
| Cold, warm and unavailable runs | `--flush-cache` (cold with `--warmup 0s`); `cache stop\|start` (outage); `cache pause\|unpause` (hang). `seed` flushes Redis. |
| Hot and uniform | The existing `detail --pattern hot\|uniform` trace (hot = 80% of reads on 20% of the products). |

## Evidence

All runs: local Docker stack `shopsphere-bench-s2`, fixture v2 (seed 20261109, 10,000 catalog products), backend 2 CPU / 1.5 GB, MySQL 2 CPU / 1 GB, Redis 1 CPU / 256 MB, k6 2 CPU on the same host. Shortened protocol: 30 s warm-up and 1 min measured per trial. The roadmap's full protocol is 2 + 5 min. Pass 1 ran without the bypass window and pass 2 with it. Raw results are in `benchmarks/results/runs/` (git-ignored). Run directory names are listed so the reports can be found again.

### SQL reads and hit ratio (target: ≥50% fewer SQL reads per successful request)

| Workload @ rate | Cache off: app SQL reads / success | Cache on: app SQL reads / success | Cache on: hit ratio |
| --- | --- | --- | --- |
| Detail, hot, 1000 req/s (warm) | 1.00–1.03 (5 trials; median 1.00) | 0.09–0.15 (5 trials; median 0.13) | 89.0–90.8% |
| Detail, uniform, 1000 req/s (warm) | — | 0.15–0.20 (6 trials) | 82.3–86.7% |
| Detail, hot, 1000 req/s (cold: flushed, no warm-up) | — | 0.14–0.20 (6 trials) | 84.3–86.3% |
| First catalog page, 500 req/s | 1.00 (6 trials) | 0.00–0.02 (6 trials) | 99.9–100% |

Each variant also had one trial that collapsed on a just-restarted JVM (3.33 reads per success with the cache off; no successes and a 70% hit ratio with it on). Both are left out of the ranges above. **Detail: −87% SQL reads per successful request** (median 1.00 → 0.13). This passes the ≥50% target. The uniform control still hits 83–87%: at 1000 req/s each of the 10,000 products is read about 6 times per 60 s TTL, which the plan's formula `λT/(1+λT)` puts at 86%. The skewed workload adds only a few points at this rate. The page workload reads 12 category first pages, so it is close to a best case for hit ratio.

Cold-cache runs show the expiry stampede the plan asked about. The keys loaded right after the flush expire together, so misses spike once a minute (525 → 417 → 295 misses/s in successive minutes, against a background of about 120/s). Those spikes did not stall the backend. TTL jitter would flatten them and is listed as a follow-up.

### Latency at matched load (target: ≥30% lower p95)

**First catalog page @ 500 req/s** (`bench.py compare`, pass 2: `20261109T214522Z_page_500rps_id` vs `20261109T212909Z_page_500rps_id`):

| Metric | Cache off | Cache on | Change |
| --- | --- | --- | --- |
| Trials passing gate | 3/3 | 3/3 | |
| Median p50 | 0.63 ms | 0.63 ms | 0% |
| Median p95 (range) | 16.7 ms (8.5–38.4) | 4.0 ms (3.0–5.9) | **−76%** |
| Median p99 | 84.0 ms | 31.7 ms | −62% |
| App SQL reads / success | 1.00 | 0.00 | −99.9% |
| Rows examined / success | 21 | 0.01 | −99.9% |
| Response bytes | 4,735 | 4,735 | same |

Pass 1 (`20261109T210758Z` vs `20261109T205512Z`) points the same way: p95 50.6 → 2.4 ms. There the cache-off variant passed only 1 of 3 trials (882 dropped iterations) and the cache-on variant passed 3/3. The median latency is identical, so the gain is in the tail. The cache takes the query, and its queueing for the 10 Hikari connections, off the path, so host stalls do less damage.

**Product detail @ 1000 req/s, hot** (passing trials only, since most trials in both variants failed the zero-drop gate on host stalls):

| | Cache off | Cache on |
| --- | --- | --- |
| Trials passing gate | 3 of 6 | 1 of 6 |
| p95 in passing trials | 1.2, 1.7, 5.0 ms | 8.7 ms |
| p50 in passing trials | 0.55–0.67 ms | 0.44 ms |

No p95 improvement on detail. The ≥30% stretch target is not met. The cache-on variant also failed more trials in pass 1, before the bypass window existed, because each GC pause cascaded into Redis timeouts. The first trial after each backend restart failed in both variants: a cold JVM took 1000 req/s from its first second and needed about 2 minutes to recover.

### Capacity (highest passing step, one trial per step)

| Ladder | Cache off | Cache on |
| --- | --- | --- |
| Pass 1 (`…202835Z` / `…203326Z`) | 1000 req/s; 1500 failed on 370 drops | 1500 req/s; 2000 failed (p95 123 ms, 1,585 drops) |
| Pass 2 (`…215003Z` / `…213838Z`) | 1000 failed (5,079 drops) | 1000 failed (2 drops) |

In pass 1, the cache raised the highest passing detail step from 1000 to 1500 req/s. Pass 2 couldn't confirm it because both variants failed their first step. Report it as an unconfirmed single observation until a quieter host repeats it.

### Redis outage and hang (target: understand fallback behavior)

Detail, hot, 1000 req/s, cache on:

| Scenario | Run | Gate | p95 | App SQL reads / success | Redis errors | Bypassed reads |
| --- | --- | --- | --- | --- | --- | --- |
| Redis stopped, no bypass (pass 1) | `20261109T205952Z` | pass | 8.2 ms | 1.00 | 120,002 | — |
| Redis stopped, with bypass (pass 2) | `20261109T213525Z` | pass | 4.7 ms | 1.00 | 183 | 119,817 |
| Redis paused (hung), with bypass | `20261109T213348Z` | FAIL (984 drops) | 103 ms | 1.00 | 5,354 | 112,686 |
| Recovery after restart, pass 1 | `20261109T210132Z` | pass | 2.5 ms | 0.09 | 0 | — |

- No request failed in any outage run. Every response was correct, served from MySQL at exactly 1.00 reads per request, with at most 10 Hikari connections.
- **Stopped Redis:** connections are refused and Lettuce rejects commands at once, so the fallback is cheap with or without the bypass. The bypass cut Redis errors from 120,002 (two per request) to 183.
- **Hung Redis:** each time the 1 s window closes, the roughly 100 requests in flight wait on one 100 ms timeout before the window reopens. That holds p95 near 100 ms, but the run still drops iterations. A half-open probe, where one request tests Redis while the rest keep bypassing, would remove this. It is listed as a follow-up.
- **Recovery:** after `cache start`, Redis is empty and the hit ratio climbs back to its warm value within one TTL. The pass-1 recovery run passed. The pass-2 recovery run failed on drops (2,126) at the same time the cache-off runs were failing too.

### Freshness

`CatalogCacheTest` checks that an admin edit or stock adjustment is visible on the very next detail and page read, because both keys are gone after commit. Changes that skip the admin API, such as checkout reservations or SQL, stay hidden until the TTL. The test shows a cached `stock: 5` while MySQL holds 0, and checkout correctly rejects the order. Configured TTL: 60 s. The observed staleness bound for non-admin changes equals the TTL. No case of a stale entry outliving its TTL was observed.

## Reproduce

```bash
cd backend && ./mvnw test
```

```bash
export BENCH_PROJECT=shopsphere-bench-s2 BENCH_BACKEND_PORT=18280 BENCH_PROMETHEUS_PORT=9290 BENCH_GRAFANA_PORT=3201
python3 benchmarks/bench.py up --observability --cache redis
python3 benchmarks/bench.py seed
python3 benchmarks/bench.py run detail --rate 1000 --pattern hot --trials 3 --warmup 30s --duration 1m
python3 benchmarks/bench.py run detail --rate 1000 --pattern uniform --trials 3 --warmup 30s --duration 1m
python3 benchmarks/bench.py run detail --rate 1000 --pattern hot --trials 3 --flush-cache --warmup 0s --duration 1m
python3 benchmarks/bench.py run page --rate 500 --trials 3 --warmup 30s --duration 1m
python3 benchmarks/bench.py cache pause    # then a 1-trial run, then: cache unpause
python3 benchmarks/bench.py cache stop     # then a 1-trial run, then: cache start
python3 benchmarks/bench.py up --observability --cache none
python3 benchmarks/bench.py run detail --rate 1000 --pattern hot --trials 3 --warmup 30s --duration 1m
python3 benchmarks/bench.py run page --rate 500 --trials 3 --warmup 30s --duration 1m
python3 benchmarks/bench.py compare <cache-off run> <cache-on run>
```

## Known limits and follow-ups

- **Quiet-host rerun.** Close the iOS Simulator and stop idle bench stacks (`shopsphere-bench`, `shopsphere-bench-s1`). Then rerun with the full 2 + 5 minute protocol, alternating variants, before quoting any latency or capacity number on a résumé. The SQL-read and hit-ratio results don't depend on host noise.
- **Warm the JVM before load.** The first trial after `up` fails in both variants, because 1000 req/s hits a cold JVM. Starting with a short low-rate warm-up, or a longer `--warmup`, after each `up` would fix it.
- **TTL jitter** (for example ±10%) would spread the once-a-minute miss spikes seen in cold runs.
- **Half-open probe** for the bypass window would remove the per-window timeout burst during a Redis hang.
- **Version guard** against the stale-refill race, only if a tighter freshness requirement appears.
- **Smoke flake.** `bench.py smoke` runs its scenarios with no warm-up. In one run, the cart check failed the p95 gate (793 ms over 76 requests) right after a backend restart and passed (22 ms) when rerun. The cart path doesn't use the cache. Give smoke a short warm-up if CI shows the same flake.
- The legacy `GET /product/` list and the cart are not cached.

## Résumé wording supported so far

- "Cut MySQL reads per product-detail request by 87% (1.00 → 0.13) at 1000 synthetic req/s with Redis cache-aside caching and post-commit invalidation, at a 90% hit ratio on a skewed workload." Measured on a shortened protocol on a laptop. Rerun on the full protocol before quoting.
- "Reduced catalog-page p95 from 16.7 ms to 4.0 ms at 500 req/s with Redis caching (3/3 trials per variant)." Note that the workload hits only first pages.
- Don't claim a detail-latency improvement. Don't claim the capacity gain until a quiet-host ladder confirms it.
