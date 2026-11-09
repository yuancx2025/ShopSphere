# Sprint 1 report · MySQL and checkout correctness

[Roadmap](../README.md) · [Sprint 1 plan](../sprints/01-mysql-checkout.md) · [Benchmarks](../../../benchmarks/README.md)

Status: implemented on `claude/sprint-zero-worksheet-review-c8c1d6`, not yet split into the planned PRs 03 and 04 or merged. The Sprint 0 harness (previously uncommitted in the `test/benchmark-foundation` worktree) is included so the branch builds and benchmarks on its own.

## Headline findings

1. **The existing checkout oversold.** The roadmap assumed `OrderService` "already reserves stock with MySQL row locks". It did take the locks, but the decrement used the `Product` entity that the cart read had loaded *before* the lock. JPA returns the managed instance for a `SELECT … FOR UPDATE` without refreshing it, so each reservation wrote `stale stock − 1` back as an absolute value. In the 100-shopper / 20-unit burst test, all 100 reservations succeeded in every trial and stock ended at 9–10. A diagnostic print showed the entity one value behind the locked row on every reservation.
2. **The same pattern was in the order state machine.** `restoreStock` loaded pending orders, then "locked" them and read status from the possibly stale entity. A release racing a payment could cancel a paid order and return its stock.
3. **Retries could never reuse the Stripe idempotency key.** `expires_at` was `now + 30 min` on every call, and Stripe rejects a reused key whose parameters changed.

4. **MySQL's plan for the id-sorted category page depended on the cursor value.** On the same data it scanned the primary key (about 250 rows examined for 21), used the right index (21 rows), or intersected two indexes and sorted. The query now pins `idx_product_category` with an `INDEX()` hint, the one hint in the codebase.
5. **The Sprint 0 harness could under-count SQL reads.** `performance_schema` can hold several rows for one digest. Keying them by digest let a zero-count row hide the real one, and a detail run reported 0 reads per request. Snapshots now sum per digest.

Findings 1–3 are fixed and covered by tests that fail on the old code (finding 1 was confirmed by running the new test against it). Findings 4 and 5 were caught by the benchmark itself.

## What was built, mapped to the plan

### PR 03 · `fix/checkout-correctness`

| Plan item | Implementation |
| --- | --- |
| Testcontainers MySQL harness | `MySqlIntegrationTest` (MySQL 8.0.43, Flyway-migrated, shared container), `Contenders` (one thread and transaction per contender, released by a latch), `TestData`. Hikari pool 10, printed with every contention result. CI no longer needs a MySQL service. |
| Deterministic fake gateway | `payment/fake/FakePaymentGateway` (`payment.provider=fake`): idempotent on the key, computes the amount from line items like Stripe, scriptable `REJECT` (the only scripted failure), late payment and tampering. Provider timeouts are not modeled (deferred). Refuses the `prod` profile. Also used by the bench stack. |
| Exact amounts | Flyway `V4__Exact_Money`: `price_cents`, `unit_price_cents`, `total_cents`, `currency`. Legacy rows are backfilled and validated with temporary CHECK constraints, so a sub-cent legacy price fails the migration by name. Legacy DOUBLE columns stay and are dual-written for rollback. API prices are `BigDecimal` with at most 2 decimals. |
| Validate the paid session | `applyPaidSession` checks order id, session id, amount and currency. A mismatch moves the order to `NEEDS_REVIEW` with a `review_reason` instead of `PAID`. |
| Stable checkout-attempt key | `Idempotency-Key` header, unique per `(user_id, checkout_key)` (V5). A replay returns the same order and session without calling the provider. The provider key is `shopsphere-checkout-<orderId>-<key>`, and every session parameter, including `expires_at`, is derived from the stored order. |
| Provider failures (kept simple) | Any create-session error cancels the attempt and returns its stock. If the provider did create a session anyway and it gets paid, the payment lands in `NEEDS_REVIEW` (`PAID_AFTER_RELEASE`) rather than being lost. No background reconciler; the webhook and the success page's reconcile call apply payments. |
| Late payment after release | `PAID_AFTER_RELEASE` → `NEEDS_REVIEW`, so the charge is never ignored (operator refunds or fulfills it by hand). |
| Admin stock writes | `stock` is `updatable = false` in JPA. The admin edit locks the row and changes only descriptive fields and price. Stock changes go through `POST /product/{id}/stock-adjustments` (a signed delta applied by one guarded UPDATE). The DB enforces `CHECK (stock >= 0)`. |
| Guarded state changes | Stock: `UPDATE … SET stock = stock - n WHERE id = ? AND stock >= n`. Orders: native `SELECT … FOR UPDATE`, decide, then a conditional `UPDATE … WHERE status = 'PENDING'`. `stock_held` on orders makes the inventory invariant one query. |

### PR 04 · `perf/database-queries`

| Plan item | Implementation |
| --- | --- |
| Bounded paginated endpoint | `GET /product/page?categoryId=&sort=id\|price_asc&size=20&cursor=`. Keyset pagination with an opaque cursor, a unique id tie-breaker, a size cap of 100, and price sorts that require a category. SQL is spelled out in `CatalogQueries` and maps rows straight to card DTOs. |
| Keep the legacy contract | `GET /product/` and `GET /product/{id}` return the same JSON shape; a test pins it. |
| React adoption | The app no longer downloads the catalog at startup. Home asks for 8 products, category pages and the admin list page with "Load more" and a sort control, and product detail and edit fetch one product. Checkout sends an `Idempotency-Key` so a double click reuses the same attempt. The edit form replaces the stock field with an adjustment form. |
| Index | `V6__Catalog_Page_Index`: `(category_id, price_cents, id)` for the price-sorted category page. The id-sorted page needs no new index (`idx_product_category` already ends in the primary key), but its query pins that index with a hint because the optimizer's choice was unstable (finding 4). |

## Evidence

All runs: local Docker stack `shopsphere-bench-s1`, fixture v2 (seed 20261109, 10,000 catalog products in 12 categories), backend 2 CPU / 1.5 GB, MySQL 2 CPU / 1 GB, k6 2 CPU on the same host. Code: this branch's uncommitted working tree on top of `b82f60c`. Shortened protocol: 30 s warm-up and 1 min measured per trial (the roadmap's full protocol is 2 + 5 min). Raw results are in `benchmarks/results/runs/` (git-ignored).

### Checkout correctness (target: zero oversells across three 100-attempt bursts)

| Evidence | Before Sprint 1 | After Sprint 1 |
| --- | --- | --- |
| `CheckoutBurstTest`: 100 shoppers, 20 units, 3 bursts (Testcontainers MySQL, threads + latch) | **100 accepted, 0 rejected, stock 9–10** in every burst | 20 accepted, 80 rejected, stock 0, held 20, in every burst |
| `bench.py burst`: same shape over HTTP through k6 | not runnable (no fake provider) | **3/3 trials pass every invariant**: 20 accepted, 80 × 409, 0 unexpected, stock 0, held 20. p95 0.87–1.20 s while 100 requests queue on one hot row (Hikari pool 10) |
| Admin edits racing 20 reservations (`AdminStockTest`) | lost update possible (absolute stock save) | stock 0, held 20; adjustments race keeps `initial + adjustments = available + held` |
| Payment paths (`PaymentOutcomeTest`: 10 scenarios, 14 runs because the expiry-vs-payment race repeats 5 times) | late payment after release ignored; no amount or currency check | duplicate, concurrent, mismatched, expired, late and rejected cases all end in the expected status with stock released at most once |

### Query plans (target: ≥30% fewer rows examined on the selected query) — `bench.py explain`

| Query (category 1, 835 products) | Before | After | Change |
| --- | --- | --- | --- |
| Price-sorted page, first page | 856 rows examined, 0.33 ms | 21 rows, 0.10 ms | **−97.5% rows** |
| Price-sorted page, cursor at row 400 | 856 rows, 0.39 ms | 21 rows, 0.07 ms | −97.5% rows |
| Id-sorted page, first page / early cursor | 241–252 rows (primary key scan) | 21 rows (pinned index) | −91% rows |

"Before" hides the V6 index with `IGNORE INDEX`, which reproduces the V5 plan on the same data, and drops the hint for the id sort.

### Pagination (target: report bytes and p95 for legacy list vs 20-item page) — `bench.py compare`

| Metric (2 req/s, 3 trials each) | Legacy `GET /product/` | `GET /product/page?size=20` | Change |
| --- | --- | --- | --- |
| Response bytes | 2,355,356 | 4,740 | **−99.8%** |
| Rows examined per request | 10,001 | 21 | −99.8% |
| Median p50 | 46.9 ms | 9.1 ms | −81% |
| Median p95 | 72.1 ms | 66.2 ms | −8% (noise at this rate) |
| Gate (p95 < 300 ms, < 1% failures, 0 drops) | 3/3 pass | 3/3 pass | |

At 2 req/s each trial has 120 samples, so p95 and p99 reflect a handful of slow outliers (cold JIT and idle connections). Use bytes, rows examined and p50 for this comparison. The page endpoint at 40 req/s (price sort, 3 trials) ran at p95 5.3–20.8 ms with 21 rows examined per request; trial 1 dropped 18 of 2,400 iterations (p99 303 ms) and fails the zero-drop gate, trials 2–3 pass. **Carry this paginated baseline into Sprint 2.**

### No regression on product detail

Detail at 40 req/s (1 trial, same protocol as the Sprint 0 run `20261109T163831Z`): p95 8.5 ms vs 7.3 ms, 1.00 SQL read and 1 row examined per request in both. The latency difference is within run-to-run noise for single trials on a laptop.

## Reproduce

```bash
cd backend && ./mvnw test
```

```bash
export BENCH_PROJECT=shopsphere-bench-s1 BENCH_BACKEND_PORT=18180 BENCH_PROMETHEUS_PORT=9190 BENCH_GRAFANA_PORT=3101
python3 benchmarks/bench.py up --observability
python3 benchmarks/bench.py seed
python3 benchmarks/bench.py explain
python3 benchmarks/bench.py burst --users 100 --stock 20 --trials 3
python3 benchmarks/bench.py run list --rate 2 --trials 3 --warmup 30s --duration 1m
python3 benchmarks/bench.py run page --rate 2 --trials 3 --warmup 30s --duration 1m
python3 benchmarks/bench.py compare <list run> <page run>
```

## Known limits and follow-ups

- `NEEDS_REVIEW` has no operator tooling yet (refund or manual fulfill). A held reservation on an amount-mismatch order stays held until someone acts.
- Payment work is deferred for the rest of the roadmap; the follow-ups are listed on the [Sprint 4 page](../sprints/04-payment-events.md).
- Deliberately left out to keep the project simple: a background reconciler for lost webhooks, special handling for provider timeouts, a stock-adjustment history table, and a price-descending sort.
- The legacy DOUBLE money columns are still written. Drop them in a later migration once no rollback target reads them.
- The price sort without a category is rejected rather than indexed.
- The burst's ~1 s p95 is the cost of 100 requests serializing on one hot row; it is a correctness result, not a latency target. Sprint 6's traffic protection is the place to address it.
- Numbers come from the shortened protocol on a laptop with k6 on the same host. Re-run the full 2 + 5 minute protocol before quoting any latency on a résumé.
