# Sprint 2 · Redis catalog cache

[Roadmap](../README.md) · [Previous sprint](01-mysql-checkout.md) · [Next sprint](03-elasticsearch-search.md)

**Time** · 1–2 weeks. **Depends on** · The paginated MySQL baseline and fixed API contract from Sprint 1.

## What to learn

- Explain cache-aside on a miss and a hit, what expiration buys you, and how cache invalidation can race with an in-flight read.
- Understand how request skew affects hit rate. Compare a hot set with uniform product reads and look for stampede behavior after expiry.
- Trace the Redis failure path and explain the tradeoff between a bounded fallback and overloading MySQL when Redis is unavailable.

## What to implement

- **Catalog reads.** Add Spring Data Redis and a cache configuration. Cache a catalog DTO or projection for product metadata and category lists. Start with product details, then cache frequently used page keys only when their hit rates justify it. Include relevant filter, sort, page, and DTO-version inputs in cache keys.
- **Stock and invalidation.** Keep inventory fields live or fetch them separately if the response promises current stock. The public detail contract and payload must match with caching enabled and disabled. Evict relevant keys after admin writes commit, use a bounded TTL, and test the stale-refill race. Document the observed staleness bound or add a version guard if freshness requirements call for it. Checkout reads and reserves stock through MySQL.
- **Infrastructure.** Add an optional Redis Compose service and a feature flag for a clean A/B run. Cap Redis timeouts, expose hit/miss/error counters, and add a bounded fallback for catalog reads. Inject a Redis outage and verify both application recovery and database pool behavior.
- **Experiment.** Run the same trace with Redis disabled, cold, warm and unavailable. For the hot workload, start with 80% of reads drawn from a 20% hot product set. Add a uniform control and a product-update probe. Record where each trial starts relative to expiration and warming.

## Target and evidence

| Target | How to prove it |
| --- | --- |
| Stretch target of ≥30% lower catalog detail P95 and ≥50% fewer SQL reads per successful request | Compare three warm, hot-workload trials at a shared load that both variants pass. Use the same endpoint contract and MySQL query path. Report the median of per-run P95s and its range. |
| Cache hit ratio and measurable freshness | Record hits divided by hits plus misses. Separately track Redis errors and bypasses. Update a known product, verify post-commit visibility, and record stale-refill behavior and the configured TTL. |
| Understand Redis outage behavior | Disconnect Redis under a load MySQL can serve, record fallback P95 and pool use, and verify response correctness and recovery after reconnection. |

The stock lookup can limit achievable SQL savings. Record total catalog SQL reads, including stock reads. A smaller improvement is worth reporting if the benchmark establishes it clearly.

## Implementation PRs

| PR | Scope | Merge gate |
| --- | --- | --- |
| 05 · `feat/redis-cache` | Cache integration, metadata projection, invalidation, TTL, fallback and local Redis | Equivalent cached and uncached responses, product edits invalidate the expected keys, and checkout still uses live MySQL inventory. |
| 06 · `test/cache-performance` | Hot/uniform scenarios, cold/warm/outage runs, counters and comparison report | Reproducible results distinguish warm-cache impact from pagination and use the same denominator for SQL reads. |

## Résumé wording

**Measured version.** “Reduced Spring Boot product-detail P95 from [A] ms to [B] ms and MySQL reads per request by [D]% at [R] RPS in k6 tests using Redis cache-aside caching.”

**Compact version.** “Cut product-read P95 by [X]% under [R] synthetic RPS with Redis caching and post-commit invalidation.”

If only database load improves, lead with SQL reads per successful request. Take `[A]`, `[B]`, `[D]`, and `[R]` from the same matched experiment. Be ready to explain the access distribution, why the cache held metadata, and how MySQL behaved during a Redis outage.

[Spring Data Redis cache integration](https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html)
