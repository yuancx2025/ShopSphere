# Sprint 2 · Redis catalog cache

[Roadmap](../README.md) · [Previous sprint](01-mysql-checkout.md) · [Next sprint](03-elasticsearch-search.md)

**Time** · 1–2 weeks. **Depends on** · The paginated MySQL baseline and fixed API contract from Sprint 1.

**Status** · Implemented; results and the stock-contract decision are in the [Sprint 2 report](../reports/sprint-02.md).

## What to learn

- Explain cache-aside on a miss and a hit, what expiration buys you, and how cache invalidation can race with an in-flight read.
- Understand how request skew affects hit rate. Compare a hot set with uniform product reads and look for stampede behavior after expiry.
- Estimate hit ratio before running. With a fixed TTL `T` and a per-key request rate `λ`, a key's hit ratio is about `λT / (1 + λT)`. Hit ratio rises with load, so a cache can look weak at a low test rate.
- Trace the Redis failure path and explain the tradeoff between a bounded fallback and overloading MySQL when Redis is unavailable.

## What to implement

- **Catalog reads.** Add Spring Data Redis and a cache configuration. Cache a catalog DTO or projection for product metadata and category lists. Start with product details, then cache frequently used page keys only when their hit rates justify it. Evaluate the paginated category listing from Sprint 1 early: its sort, limit and count work costs more than a primary-key lookup and is the more likely source of savings. Include relevant filter, sort, page, and DTO-version inputs in cache keys.
- **Stock and invalidation.** `ProductDto` currently includes `stock`. If a cached detail response still reads live stock from MySQL, every hit runs one query, and the route stays at one SQL read per request before and after caching. Decide the contract first: move exact stock to a separate field or endpoint, or show a coarse in-stock flag with a short TTL. Keep inventory fields live or fetch them separately if the response promises current stock. The public detail contract and payload must match with caching enabled and disabled. Evict relevant keys after admin writes commit, use a bounded TTL, and test the stale-refill race. Document the observed staleness bound or add a version guard if freshness requirements call for it. Checkout reads and reserves stock through MySQL.
- **Infrastructure.** Add an optional Redis Compose service and a feature flag for a clean A/B run. Cap Redis timeouts, expose hit/miss/error counters, and add a bounded fallback for catalog reads. Inject a Redis outage and verify both application recovery and database pool behavior.
- **Experiment.** Run the same trace with Redis disabled, cold, warm and unavailable. For the hot workload, start with 80% of reads drawn from a 20% hot product set. Add a uniform control and a product-update probe. Record where each trial starts relative to expiration and warming. Compare at a rate near the uncached variant's highest passing step from Sprint 0, not at the 40 RPS calibration rate. At 40 RPS a primary-key lookup leaves little latency to save, and the extra Redis round trip can raise P95.

Modeled hit ratio for the 80/20 hot set on 10,000 products, assuming Poisson arrivals and expire-after-write TTLs. Record observed ratios rather than quoting these.

| Offered RPS | TTL 60 s | TTL 300 s | TTL 1 h |
| --- | --- | --- | --- |
| 40 | 40% | 71% | 94% |
| 200 | 71% | 89% | 99% |
| 1,000 | 89% | 97% | 100% |

## Target and evidence

| Target | How to prove it |
| --- | --- |
| Stretch target of ≥30% lower catalog detail P95 and ≥50% fewer SQL reads per successful request | Compare three warm, hot-workload trials at a shared load that both variants pass, near the uncached variant's highest passing step. Use the same endpoint contract and MySQL query path. Report the median of per-run P95s and its range. The SQL target requires stock to be off the cached read path. |
| Capacity gain | Find the highest passing step with Redis off and on under the same gate, trace and topology. A capacity increase is often clearer than P95 at a matched load. |
| Cache hit ratio and measurable freshness | Record hits divided by hits plus misses. Separately track Redis errors and bypasses. Update a known product, verify post-commit visibility, and record stale-refill behavior and the configured TTL. |
| Understand Redis outage behavior | Disconnect Redis under a load MySQL can serve, record fallback P95 and pool use, and verify response correctness and recovery after reconnection. |

With live stock in the same response, the detail route cannot drop below one SQL read per request. Record total catalog SQL reads, including stock reads. A smaller improvement is worth reporting if the benchmark establishes it clearly.

## Implementation PRs

| PR | Scope | Merge gate |
| --- | --- | --- |
| 05 · `feat/redis-cache` | Cache integration, metadata projection, invalidation, TTL, fallback and local Redis | Equivalent cached and uncached responses, product edits invalidate the expected keys, and checkout still uses live MySQL inventory. |
| 06 · `test/cache-performance` | Hot/uniform scenarios, cold/warm/outage runs, counters and comparison report | Reproducible results distinguish warm-cache impact from pagination and use the same denominator for SQL reads. |

## Résumé wording

**Measured version.** “Reduced Spring Boot product-detail P95 from [A] ms to [B] ms and MySQL reads per request by [D]% at [R] RPS in k6 tests using Redis cache-aside caching.”

**Compact version.** “Cut product-read P95 by [X]% under [R] synthetic RPS with Redis caching and post-commit invalidation.”

**Capacity version.** “Raised the highest passing product-read load from [X] to [Y] RPS at [P] ms P95 with Redis cache-aside caching, cutting MySQL reads per request by [D]%.”

If only database load improves, lead with SQL reads per successful request. Take `[A]`, `[B]`, `[D]`, and `[R]` from the same matched experiment. Be ready to explain the access distribution, why the cache held metadata, and how MySQL behaved during a Redis outage.

[Spring Data Redis cache integration](https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html)
