# ShopSphere benchmarks

Sprints 0–2 of the [ShopSphere reboot](../engineering/shopsphere-reboot/README.md): a reproducible load-test
harness for the catalog and checkout APIs, with server-side metrics to explain the results. Sprint 1 added the
paginated catalog scenario, the checkout contention burst, query-plan capture and run comparison
([Sprint 1 report](../engineering/shopsphere-reboot/reports/sprint-01.md)). Sprint 2 added the Redis catalog cache
switch, hit/miss/error counters per trial, cold-cache runs and outage injection
([Sprint 2 report](../engineering/shopsphere-reboot/reports/sprint-02.md)).

Everything runs in Docker. You need Docker with Compose v2 and Python 3.10 or newer. k6 runs from the
pinned `grafana/k6` image, so you don't need to install it.

## Quick start

```bash
python3 benchmarks/bench.py up --observability
```

```bash
python3 benchmarks/bench.py seed
```

```bash
python3 benchmarks/bench.py run detail --rate 40 --pattern hot --trials 3
```

Each run writes `report.md`, `manifest.json` and per-trial k6 summaries to `benchmarks/results/runs/<timestamp>_…/`.
Grafana is at <http://127.0.0.1:3001> (anonymous read-only, admin password `bench-admin`) and Prometheus
is at <http://127.0.0.1:9090>.

## What's here

| Path | Purpose |
| --- | --- |
| `bench.py` | Runner: `up`, `seed`, `run`, `ladder`, `burst`, `explain`, `compare`, `cache`, `smoke`, `down`. Standard library only. |
| `docker-compose.bench.yml` | Dedicated stack (project `shopsphere-bench`): MySQL, Redis, backend with the `bench` profile, optional Prometheus and Grafana. It doesn't touch the dev stack's containers, ports or database. |
| `seed/generate_fixture.py` | Deterministic fixture (v2): 10,000 catalog products in 12 categories with exact `price_cents`, a hot SKU with 20 units, 200 shoppers and 100 carts. Same seed, same SQL bytes. |
| `k6/product-detail.js` | `GET /product/{productID}`, uniform or hot-set (80% of reads on 20% of products) access. |
| `k6/product-list.js` | Legacy `GET /product/` (whole catalog). Use a low rate. |
| `k6/product-page.js` | `GET /product/page`: first page of a category per request, checked for size, category and sort order. |
| `k6/checkout-skeleton.js` | Reads each shopper's cart with tokens `bench.py` signed in before the run. |
| `k6/checkout-burst.js` | One `POST /order/create-checkout-session` per shopper, all at once, against the fake payment gateway. |
| `observability/` | Prometheus scrape config and the provisioned Grafana dashboard. |

## Commands

| Command | What it does |
| --- | --- |
| `up [--observability] [--cache none\|redis]` | Builds the backend image and starts the stack, waiting for health checks. `--cache` sets the backend's catalog cache (the A/B switch) and recreates the backend; without it, `$BENCH_CACHE` or `none` is used. |
| `seed [--products N --seed S]` | Generates the fixture, resets the bench database to it and verifies the counts. |
| `run SCENARIO --rate R [--pattern hot] [--trials 3 --warmup 2m --duration 5m]` | For each trial, a warm-up k6 run, then a measured run with MySQL digests snapshotted around it. |
| `run page --rate R --sort id\|price_asc [--size 20]` | Same as `run`, for the paginated catalog. |
| `ladder SCENARIO --rates 40,200,500,1000,2000` | One shorter trial per rate (30 s warm-up, 1 min measured). Stops at the first step that fails the gate, then prints the command to confirm the highest passing step. |
| `run … --flush-cache` | Empties Redis before each trial. With `--warmup 0s` the trial measures a cold cache; with a warm-up it measures a cache warmed from empty. |
| `cache stats\|flush\|stop\|start\|pause\|unpause` | Shows the backend's cache mode and its hit, miss, Redis error and bypass counters, or empties Redis. `stop`/`start` inject an outage (Redis gone, connections refused); `pause`/`unpause` inject a hang (connections open, no replies). The backend keeps running either way. |
| `burst [--users 100 --stock 20 --trials 3]` | Checkout contention. Before each trial it resets orders, carts and the hot SKU's stock; after it, it checks the database: accepted = stock, rejected = the rest, stock never negative, and initial stock = available + units held by orders. |
| `explain [--category 1]` | `EXPLAIN ANALYZE`, rows examined and server time for each catalog query, with and without the V6 price index. |
| `compare RUN_A RUN_B [--out file.md]` | Side-by-side medians (p50/p95/p99, delivered and successful RPS, failures, drops, SQL reads per success, bytes) with the change in percent. Warns when the runs used different resource limits. |
| `smoke` | Small fixture and 15-second runs. It checks that correct responses pass the gate, that a wrong expected id fails it, that a category page is full and sorted, and that a 20-shopper checkout burst keeps the inventory invariants. CI runs this. Re-seed afterwards. |
| `down [--volumes]` | Stops the stack, and optionally deletes the bench database volume. |

Gate options apply to `run`, `ladder` and `smoke`: `--p95-ms 300`, `--max-failure-rate 0.01` and `--timeout 2s`.
Set `BENCH_PROJECT` (and `BENCH_BACKEND_PORT`, `BENCH_PROMETHEUS_PORT`, `BENCH_GRAFANA_PORT`) to run a second, fully
separate stack, for example one per git worktree. Each project has its own database volume, so a branch with newer
Flyway migrations never touches another branch's bench database. Don't measure two stacks at the same time: they
share the Docker VM's CPUs.

The CPU and memory limits come from `BENCH_BACKEND_CPUS` (2), `BENCH_BACKEND_MEM` (1536m), `BENCH_MYSQL_CPUS` (2),
`BENCH_MYSQL_MEM` (1g), `BENCH_REDIS_CPUS` (1), `BENCH_REDIS_MEM` (256m) and `BENCH_K6_CPUS` (2). `BENCH_CACHE_TTL`
(60s) sets the cache TTL. Keep them fixed within an A/B comparison. Every manifest records them.

## How a measurement works

- **Open model.** k6 uses `constant-arrival-rate`: it starts `RATE` iterations per second whether or not earlier
  ones finished. Each iteration is one HTTP request. If the server or the generator can't keep up, the run
  shows **dropped iterations**. Any drop fails the gate, because the offered load was not delivered.
- **Correctness.** A request counts as successful only when the status is 200 *and* the body is the one
  requested (detail checks the product id, list checks the full count, cart checks the hot SKU). Anything else,
  including timeouts, counts as an unexpected failure.
- **Gate.** Route p95 under 300 ms, fewer than 1% unexpected failures, and zero dropped iterations. This is the
  provisional gate from the roadmap. Tune it after the baseline, then freeze it before comparing variants.
- **Deterministic trace.** The product id for each iteration is a hash of the global iteration number and
  `--trace-seed`, so a given seed, pattern, rate and duration replays the same requests.
- **Authenticated scenarios.** `bench.py` signs the shoppers in over HTTP before the measured run and hands k6 the
  tokens, so bcrypt sign-ins and their user lookups stay out of latency and SQL counts. (Sprint 0 signed in inside
  k6 `setup()`, which landed in the SQL window and doubled the cart scenario's SQL reads per success.)
- **SQL attribution.** `bench.py` snapshots `performance_schema.events_statements_summary_by_digest` for the bench
  schema right before and after the measured run. `App SQL reads / success` counts `SELECT`s on application tables
  divided by successful requests. `sql-digests.json` lists every statement, including `SET` and `COMMIT` round trips.
- **Cache counters.** `bench.py` reads `cache_gets_total{result="hit|miss"}` and `catalog_cache_errors_total` from the
  backend's internal Prometheus endpoint before and after the measured run. Each trial reports the hit ratio
  (hits / (hits + misses)), the Redis errors, and the reads that bypassed Redis after an error
  (`catalog_cache_bypassed_total`) for the route's cache (`productDetail` or `catalogPage`). With
  `--cache none` these columns show `n/a`. `seed` flushes Redis, because the fixture changes rows under the cache.
- **Separate warm-up.** The warm-up is its own k6 run, so its requests and SQL never mix into the measured window.
  The gap between the two runs is a second or two of container start-up.

## Known limits

- Keep the machine plugged in and awake. On macOS, `bench.py` runs `caffeinate -i` for the duration of a run,
  but closing the lid on battery still sleeps the machine. A trial whose measured window takes much longer than
  its duration is marked `INVALID` and fails, because a pause corrupts latency and drop counts.

- k6 runs in Docker on the same machine and shares the Docker VM's CPUs with the stack. The CPU limits keep
  the share fixed, but the generator is not isolated. Note this next to any number you publish.
- `/actuator/prometheus` is only exposed by the `bench` profile, on management port 8081, which the stack
  never publishes to the host. Port 8080 serves no actuator endpoints in this profile.
- Results under `benchmarks/results/` are git-ignored. Copy the `report.md` of a run you want to keep into the
  sprint report, along with its reproduction command.
- The test-only shopper password is in the fixture manifest. It exists only in the bench database.
