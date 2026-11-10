# Sprint 2 cloud benchmark

[Sprint 2 plan](../sprints/02-redis-cache.md) · [Original laptop report](sprint-02.md) · [Cloud artifacts and logs](https://github.com/yuancx2025/ShopSphere/actions/runs/42724074535)

The cloud runs used the 10,000-product fixture, the same anonymous request trace, and the Sprint 2 gate of p95 below 300 ms, fewer than 1% unexpected failures, and zero dropped iterations. The GitHub-hosted Linux runners had four vCPUs. The backend, MySQL, k6, and Redis CPU caps were 1.5, 1, 1, and 0.5 respectively. Each pair ran sequentially on the same runner using one backend image from a clean commit. The order reversed in the second of three pairs. Each warm trial used two minutes of warm-up and five minutes of measurement. The ladder used 30 seconds of warm-up and one minute of measurement per step.

The comparison command now checks the image, fixture, trace, workload settings, durations, gate, and limits. Redis bypass metrics distinguish gets from puts. The cloud jobs saved the raw request summaries, SQL digests, manifests, and sampled Redis, JVM, and connection-pool telemetry.

## Tests

- **39 backend tests passed** on GitHub with real MySQL and Redis containers, including six cache tests. The added concurrency test held a database read open across an admin edit and eviction, let the old value refill Redis, and verified that the new value appeared after the 60-second entry expired.
- The Redis hang integration test verified 20 bypassed gets and 21 bypassed puts across 21 sequential requests, then verified that cache fills resumed after recovery.
- **Two Python tests passed** for the metric split and comparison checks.

[Cloud test job](https://github.com/yuancx2025/ShopSphere/actions/runs/42724074535/job/129525076251)

## Steady-state A/B

All three cache-off and cache-on trials for each 500 req/s workload passed with zero dropped iterations and zero unexpected failures. Each p95 below is the median of the three trial p95s. SQL reads use the successful-request denominator.

| Workload and measure | Original laptop report | Cloud |
| --- | --- | --- |
| First-page p95 at 500 req/s | 16.7 → 4.0 ms, 76% lower | **0.839 → 0.630 ms, 24.9% lower**. Off range 0.780–0.839 ms. On range 0.625–0.692 ms. |
| First-page SQL reads per success | 1.00 → about 0.00067, 99.9% fewer | **0.99996 → 0.00040, 99.96% fewer**. Median Redis hit ratio 99.96%. |
| Hot detail p95 | At 1,000 offered req/s, 3/6 off and 1/6 on passed | At 500 req/s, **1.309 → 1.591 ms, 21.6% higher**. Off range 1.308–1.327 ms. On range 1.575–1.760 ms. Median p50 fell 1.082 → 0.557 ms. |
| Hot detail SQL reads per success | At 1,000 offered req/s, 1.00 → 0.13, 87% fewer | At 500 req/s, **1.000 → 0.175, 82.5% fewer**. Median Redis hit ratio 82.45%. |

The first-page result covers one sort and page size across 12 category first pages. The cloud reproduced the SQL reduction and a smaller first-page p95 gain. The difference in the two latency gains combines changes in the host, container limits, and measurement windows.

## Cold cache and uniform control

The single uniform pair at 500 req/s passed on both sides. SQL reads per success fell from 1.000 to 0.247, with a 75.34% Redis hit ratio. P95 went from 1.388 to 1.822 ms. After that warm trial, the cold test flushed Redis while leaving the JVM and MySQL warm. The cold hot-trace run passed at 500 req/s with an 81.43% hit ratio, 0.186 SQL reads per success, and 1.817 ms p95. The 15-second miss-rate samples peaked at 234, 178, 152, 143, and 129 misses/s in successive minutes. This shows a declining refill pattern at the cloud test's rate and sampling interval.

## Redis outage and recovery

The uncached baseline passed at 250 req/s. At that rate, all injected outage and recovery windows passed. Each issued request met the HTTP status and expected product-ID checks.

| Scenario | Cloud p95 | Dropped iterations | SQL reads per success | Bypassed gets |
| --- | ---: | ---: | ---: | ---: |
| Uncached MySQL baseline | 1.01 ms | 0 | 1.000 | — |
| Redis stopped | 1.15 ms | 0 | 1.000 | 14,966 |
| Redis restarted | 1.55 ms | 0 | 0.285 | 0 |
| Redis paused | 101.28 ms | 0 | 0.999 | 13,724 |
| Redis unpaused | 1.55 ms | 0 | 0.285 | 0 |

Prometheus sampled zero waiting database connections in each outage window. The stopped and paused tests recorded 60 and 1,295 Redis errors respectively. The paused p95 was close to the laptop's 103 ms p95. The laptop dropped 984 iterations at 1,000 offered req/s, four times the cloud outage rate.

## Near-capacity detail A/B

[Full 1,250 and 1,500 req/s trials](https://github.com/yuancx2025/ShopSphere/actions/runs/42727130793)

| Offered req/s | Cache-off passes | Cache-on passes | Result |
| ---: | ---: | ---: | --- |
| 1,250 | 3/3 | 3/3 | Median SQL reads per success fell **0.9984 → 0.09393**, a **90.6% reduction**. Median Redis hit ratio was 90.55%. The median of trial p95s fell **3.759 → 1.863 ms**. |
| 1,500 | 1/3 | 1/3 | Two pairs dropped iterations and had high latency. The passing pair delivered about 1,500 successful req/s in both variants. |

The 1,250 req/s p95 comparison varied by runner. The three paired changes were **−54.1%, +3.5%, and +9.4%**. The aggregate median crossed the sprint's 30% stretch threshold, with a decrease in one of three pairs. The SQL reduction held in every pair. The highest rate to pass all three five-minute trials was **1,250 req/s for both variants**.

In the initial stepped ladder, both variants passed through 1,500 req/s for one minute per rate. At the original report's 1,000 req/s, that ladder observed p95 of 10.80 → 3.36 ms and SQL reads per success of 0.997 → 0.121. This is one passing pair at a matched offered rate. A subsequent ladder that started directly at 1,500 req/s overloaded both variants before it reached any higher step.

## Gradual capacity ladder

[Extended gradual ladder](https://github.com/yuancx2025/ShopSphere/actions/runs/42728101662)

The final ladder started at 250 req/s and stepped up through the same rates on both variants. Each step measured one minute after a 30-second warm-up.

| Variant | Highest passing step | First failing step |
| --- | ---: | --- |
| Cache off | 2,250 req/s | 2,500 req/s at 413.4 ms p95 and 608 drops |
| Cache on | 2,500 req/s | 3,000 req/s at 194.6 ms p95 and 67 drops |

At 2,250 req/s both variants passed. P95 fell **125.92 → 28.53 ms**, and SQL reads per success fell **0.997 → 0.062**. Five-second pool samples peaked at 184 waiting connections with caching off and zero with caching on. At 1,000 req/s, this ladder showed a smaller latency difference, **1.161 → 1.129 ms**, and SQL reads fell **0.990 → 0.122**. The roughly 88% SQL reduction at 1,000 req/s held across both cloud ladders and was close to the laptop's 87%.

The gradual ladder's highest passing steps rose from 2,250 to 2,500 req/s, an 11% difference in this one-minute experiment. The laptop's short ladder rose from 1,000 to 1,500 req/s and failed to repeat at 1,000 in its second pass. For sustained capacity, the three five-minute trials per variant establish 1,250 req/s on these runners. The higher ladder steps need their own warmed five-minute confirmation before quoting a sustained capacity gain.
