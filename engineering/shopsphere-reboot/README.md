# ShopSphere reboot

An implementation plan for the existing Java/Spring Boot, React, and MySQL application. It covers **8 sprints and 15 implementation PRs**, estimated at **18–24 weeks at 15–20 hours per week**. The sprint ranges below add up to 13–19 weeks; the rest leaves room for benchmark reruns, debugging, and the test harness Sprint 1 builds from scratch. The first checkpoint is Sprints 0–2, which produce a working benchmark harness and a controlled Redis/MySQL comparison. Sprints 0–2 plus Sprint 4 already make a complete portfolio entry if later sprints slip. The final portfolio package combines catalog performance, checkout and event reliability, and a reproducible deployment.

Every target below is provisional. Record a baseline, choose a passing threshold before comparing versions, and publish the observed result with its workload and environment. The résumé drafts describe future milestones.

## Repository starting point

The plan was checked against [ShopSphere main at 0ad843f](https://github.com/yuancx2025/ShopSphere/commit/0ad843f199887fbb17c88ac30f5abf5719f4ad0a). Product list and detail are `GET /product/` and `GET /product/{productID}`. The list calls JPA `findAll()`. `OrderService` already reserves stock with MySQL row locks and uses a provider-neutral `PaymentGateway` (Sprint 1 found those locks did not prevent oversells; see the [Sprint 1 report](reports/sprint-01.md)). `StripeWebhookController` verifies signatures and handles events synchronously with a processed-event table. Spring Boot Actuator, Flyway migrations, Docker Compose, and GitHub Actions are already present.

A review against the code found gaps the sprints must account for. The backend has one context-load test and no integration tests. `ProductService.updateProduct` saves the admin form's absolute stock value without a lock. Order totals and Stripe line items are rounded separately from `double` prices. `OrderService.fulfillCheckout` ignores a paid session whose order was already cancelled. `JwtAuthenticationFilter` loads the user from MySQL on every request that sends a bearer token. Sprint 1 covers the first four, and the benchmark contract below covers the last.

Paths inside the sprint pages identify existing code or proposed additions.

| Sprint | Time | Implementation PRs | Evidence to collect |
| --- | --- | --- | --- |
| [0 · Benchmark foundation](sprints/00-benchmark-foundation.md) | 1–2 weeks | 01–02 | Reproducible workload, per-route P95, database and JVM baselines |
| [1 · MySQL and checkout correctness](sprints/01-mysql-checkout.md) | 2–3 weeks | 03–04 | Inventory invariants, equivalent-query plans, payload-size change |
| [2 · Redis catalog cache](sprints/02-redis-cache.md) | 1–2 weeks | 05–06 | Catalog P95, SQL reads per successful request, hit ratio |
| [3 · Elasticsearch search](sprints/03-elasticsearch-search.md) | 2–3 weeks | 07–08 | NDCG@10, search P95, indexing lag |
| [4 · Stripe inbox and transactional outbox](sprints/04-payment-events.md) | 2 weeks | 09–10 | Webhook acknowledgment P95, deduplicated transitions, restart recovery |
| [5 · RocketMQ and workers](sprints/05-rocketmq-workers.md) | 2–3 weeks | 11–12 | Events/sec, end-to-end lag, backlog recovery, duplicate effects |
| [6 · Traffic protection](sprints/06-traffic-protection.md) | 1–2 weeks | 13 | Accepted goodput and latency under bursts, 429s, errors |
| [7 · AWS and final evaluation](sprints/07-aws-final-evaluation.md) | 2 weeks | 14–15 | Deployment smoke/rollback checks and the final capacity report |

PR numbers above are planning labels. Each sprint page contains the scope and merge gates for those implementation PRs.

## Scope and dependencies

- **First release.** Use MySQL, Redis, k6, Spring Boot Actuator/Micrometer, and an optional local Prometheus/Grafana profile. Preserve the Java/Spring Boot application and React client.
- **Search and reliability.** Elasticsearch is the search projection. MySQL owns catalog, orders, and inventory. Use the Stripe inbox and transactional order outbox before adding RocketMQ for post-payment work. Search can proceed after the paginated catalog is stable. Payment inbox work depends on checkout correctness and can proceed alongside search. Prefer finishing Sprint 4 before Sprint 3: it builds on the Sprint 1 checkout code while it is fresh, and its correctness evidence holds up better than a relevance score.
- **Overload and deployment.** Add the shared Redis limiter after the Redis integration. Use EC2 and Docker Compose for a short-lived staging demo, with a smaller service profile if needed. Keep the full test matrix local unless a cloud run warrants the expense.
- **Deferred scope.** Revisit sharding, Snowflake IDs, Spring Cloud Gateway, OpenFeign, Sentinel, Kubernetes, or managed search only when there is a concrete need to measure. RocketMQ is the selected broker for the core build.

## Shared benchmark contract

Use synthetic fixtures and a dedicated test stack. The exception is Sprint 3's relevance evaluation, which needs a public labeled dataset. Start with a deterministic 10,000-product catalog and add a 100,000-product scale case after bounded pagination. Keep seed, dataset, request trace, endpoint contract, JVM/container limits, and generator placement fixed for each A/B comparison. Use the same warm-up and measurement windows, alternate variant order when feasible, and run at least three valid trials. A workable starting protocol is 2 minutes of warm-up followed by 5 minutes of measured load. Run cold-cache and warm-cache scenarios separately.

Start with one HTTP request per k6 arrival-rate iteration for capacity comparisons. A shopping journey can contain multiple requests, so report journeys/sec and achieved HTTP RPS separately. Record scheduled iterations, dropped iterations, delivered requests, successful goodput, expected rejections, unexpected errors including timeouts, and latency per route and outcome. Compare SQL reads per successful request over the same measurement window. Capture the two commit SHAs or feature-flag settings, hardware, image versions, trial summaries, and raw artifact locations. Store compact summaries and a reproduction command in the repository.

Treat the 40 RPS and 20 deliveries/sec starting rates in the sprint pages as calibration points. By Little's law, 40 RPS at 10 ms keeps fewer than one request in flight, which stresses nothing. Step each route's load until the gate fails, record the highest passing step, and run A/B comparisons near that point. Send catalog and search requests without an `Authorization` header so the per-request user lookup does not count toward catalog SQL reads. Set an explicit k6 request timeout, for example 2 seconds, so overload appears as timeouts rather than slow successes. Run only the Compose profile a sprint needs and set container memory and JVM heap limits explicitly. Elasticsearch, RocketMQ, two API replicas and monitoring together do not fit comfortably beside k6 on a 16 GB laptop.

Start with a **provisional steady-state gate** of route P95 under 300 ms for the catalog reads, fewer than 1% unexpected failures, and zero dropped iterations or capacity-related 429s. Tune the gate after the baseline and freeze it before the comparison. Capacity is the highest **tested passing load** under that gate, with achieved successful RPS and duration reported alongside it. Flash-sale runs use a separate overload protocol that counts intentional 429s and verifies that protected traffic continues to succeed. Checkout and messaging runs have separate database invariants.

Useful references are [k6 constant arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/), [k6 dropped iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/), and [Spring Boot 3.2.2 Actuator](https://docs.spring.io/spring-boot/docs/3.2.2/reference/html/actuator.html).

## Cost envelope

| Setup | Planning estimate | Scope |
| --- | --- | --- |
| Local development and load tests | $0 in cloud service charges | Run only the Compose services needed for each sprint. |
| Temporary AWS staging | $5–$25 allocated | Allow room for a second load-generator host, retries, and incidental charges. Terminate or clean up each run. |
| Always-on AWS demo | Roughly $37/month for a small profile or $67/month for a larger one | Illustrative estimates before transfer, snapshots, extra CPU credits, taxes, or optional services. See [Sprint 7](sprints/07-aws-final-evaluation.md) for assumptions. |

## Résumé documents

- [Target résumé entries](resume/target-resume.md) has a three-bullet backend version, a platform-oriented version, and a first-release version.
- [Résumé evidence and wording guide](resume/claim-evidence.md) maps each potential claim to the run, data, and decision needed to defend it.
