# Sprint 0 · Benchmark foundation

[Roadmap](../README.md) · [Next sprint](01-mysql-checkout.md)

**Time** · 1–2 weeks. **Starting point** · Current `GET /product/` returns the whole catalog. `GET /product/{productID}` is the first stable endpoint for an apples-to-apples comparison across later sprints.

## What to learn

- Explain the difference between virtual users, in-flight requests, scheduled iterations, delivered HTTP RPS, and successful goodput.
- Choose fixed arrival rate for comparable server load and fixed VUs with think time for shopper journeys. Explain what dropped iterations reveal about the generator or the application.
- Explain why a per-route P95 tells a different story from an average. Use k6 for client timing and Spring/Micrometer histograms for server-side diagnosis.

## What to implement

- **Benchmark code.** Add `benchmarks/seed/` with deterministic synthetic products, categories, users, carts and a hot-SKU inventory fixture. Record the seed, fixture version, and known IDs. Add k6 scripts in `benchmarks/k6/` for catalog/list and detail. Prepare a checkout scenario skeleton that uses a fake provider once Sprint 1 supplies it.
- **Workload.** Start the detail test at 40 offered requests/sec with one request per iteration. Test uniform and hot-key access separately. Use a modest dataset for the legacy unbounded list and record response bytes. Add an explicit check for the expected product ID or list shape so HTTP 200 alone does not pass.
- **Infrastructure.** Add the Prometheus Micrometer registry in `backend/pom.xml`, enable HTTP duration buckets in a benchmark profile, and keep `/actuator/prometheus` internal. Add a Compose overlay and a Grafana dashboard with per-route request rate, error rate, latency, Hikari pool pressure and JVM CPU/heap. Use route templates as tags. Measure database reads with deltas from MySQL statement digests or narrowly scoped application counters.
- **Reproduction.** Document how to start the stack, reset fixtures, warm up, run the test and save the results. Save the raw k6 summaries and a run manifest. Give full capacity tests a manual entry point and use a small smoke run for CI.

## Target and evidence

| Target | How to prove it |
| --- | --- |
| Three repeatable baseline trials at an initial 40 RPS on the detail route | Two-minute warm-up and five-minute measurement per trial. Start with 12,000 scheduled single-request iterations per trial and record actual delivered requests. Use a lower calibration rate if 40 RPS fails. |
| A valid, understandable run | Record P50/P95/P99, delivered RPS, successful goodput, error and timeout counts, dropped iterations, payload size, and SQL reads per successful request. Diagnose any drops before using the run for a performance comparison. |
| Baseline bottleneck | Save one load step with corresponding dashboard evidence of the first observable constraint, such as connection-pool saturation or application CPU. Label this as an observation unless a follow-up experiment isolates the cause. |

## Implementation PRs

| PR | Scope | Merge gate |
| --- | --- | --- |
| 01 · `test/benchmark-foundation` | Fixture generator, catalog k6 scenarios, run instructions, manifest and first summaries | A fresh test database can reproduce the fixture and run the script. An incorrect response fails the checks. |
| 02 · `infra/observability` | Actuator metrics, Prometheus scrape configuration, Grafana dashboard and SQL counters | One complete test run can be traced from client latency to service and database metrics. The metrics endpoint is internal. |

## Résumé wording

**Measured version.** “Built a reproducible k6 benchmark suite for Spring Boot catalog APIs, generating [R] requests/sec over [N] seeded products and correlating per-endpoint P95 with MySQL and JVM metrics in Grafana.”

**Compact version.** “Instrumented Spring Boot catalog APIs with Micrometer and Prometheus and built k6 workloads to track tail latency, errors, and database reads under synthetic load.”

Use `[R]` for delivered HTTP request rate, `[N]` for the catalog size used in the same run. Keep the baseline detail in the portfolio write-up if Sprint 2 supplies a stronger résumé result. In an interview, explain how you verified that the generator delivered the offered traffic.

[Grafana k6 metric definitions](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/) · [Spring Boot 3.2.2 Actuator](https://docs.spring.io/spring-boot/docs/3.2.2/reference/html/actuator.html)
