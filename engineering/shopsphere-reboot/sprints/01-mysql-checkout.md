# Sprint 1 · MySQL and checkout correctness

[Roadmap](../README.md) · [Previous sprint](00-benchmark-foundation.md) · [Next sprint](02-redis-cache.md)

**Time** · 1–2 weeks. **Depends on** · Sprint 0's fixtures and measurement harness.

## What to learn

- Trace `OrderService` from stock reservation and session creation through payment fulfillment, reconciliation, and stock release. Identify where the order lock and product-row locks serialize state changes.
- Understand exact monetary representation, stable idempotency keys, ambiguous provider timeouts, and why a paid session needs amount, currency and order validation.
- Read `EXPLAIN ANALYZE`, recognize unnecessary rows examined and JPA N+1 access, and choose a composite index that matches the filter and sort. Understand stable pagination and why a change in response size is a separate experiment.

## What to implement

- **Checkout.** Work through `OrderService`, the repositories, `PaymentGateway` and its Stripe adapter. Add a deterministic fake gateway for tests. Persist exact amounts through a Flyway migration and validate legacy rows and rounding before switching reads and writes. Confirm the paid provider session against the expected order, amount, and currency. Persist a stable checkout-attempt key and bind it to the user and order. Retry the same attempt with the same provider idempotency key. Resolve unknown create-session outcomes through retry or reconciliation before releasing stock. Define an explicit review outcome for a late confirmed payment after stock release.
- **Correctness tests.** Use MySQL integration tests with separate transactions for contenders. Cover simultaneous users on one SKU, repeated checkout requests, conflicting reconcile and webhook paths, invalid payment details, and expiration or late payment. Exercise existing row-lock behavior before attributing improvements to a new mechanism.
- **Catalog API.** Add a bounded paginated endpoint, for example `GET /product/page?categoryId=1&size=20`, and migrate React catalog callers to it. Keep the existing `/product/` response contract available during the migration. Use stable ordering with a unique ID tie-breaker, cap page size, and return only the fields the page needs.
- **MySQL.** Inspect the existing category/name indexes in Flyway V1. Capture a baseline plan on the new paginated query, then test a projection and a composite index that matches the selected query. Use a new migration and validate the access path and query results on realistic data.

## Target and evidence

| Target | How to prove it |
| --- | --- |
| Zero oversells or negative stock across three 100-attempt bursts | Use 100 distinct seeded users, one unit per request, stock of 20, and no concurrent release during the initial burst. With the healthy fake provider, assert exactly 20 accepted reservations and 80 explicit stock rejections. Reconcile initial stock against available, reserved, and sold units. |
| Correct payment and release behavior | Run deterministic duplicate, timeout, amount/currency mismatch, expiration, and late-payment cases. Verify terminal outcomes and released stock once at the database boundary. |
| Fewer rows examined or lower latency on an equivalent paginated query | Compare the same query contract and seed before and after the index or projection change. Aim for at least 30% fewer rows examined on the selected bottleneck query. If the optimizer already uses a good access path, document the evidence and keep the smaller change. |
| Bounded list response | Report returned bytes and route P95 for the legacy full list and the new 20-item endpoint as a separate pagination comparison. Carry the paginated MySQL baseline into Sprint 2. |

## Implementation PRs

| PR | Scope | Merge gate |
| --- | --- | --- |
| 03 · `fix/checkout-correctness` | Fake gateway, MySQL concurrency tests, exact amounts and guarded payment transitions | Tests cover duplicate, mismatched, late and ambiguous payment paths. Inventory reconciles after contention and release. |
| 04 · `perf/database-queries` | Paginated API, React adoption, SQL tuning and Flyway changes if justified | Pagination and legacy compatibility checks pass. PR includes comparable query plans, payload sizes and k6 summaries. |

## Résumé wording

**SQL result.** “Optimized Spring Boot catalog queries with bounded pagination and MySQL indexes, reducing rows examined by [X]% for [filter/sort] across [N] seeded products.” Use the indexing wording only if the index was implemented and the query plans support the result.

**Checkout result.** “Hardened Stripe checkout with exact-amount validation and MySQL row-locked inventory reservations, observing zero oversells across [N] synthetic checkout attempts.” If the work mainly verified the existing locking mechanism, use “Validated MySQL inventory locking” in place of language that implies it was newly implemented.

For interviews, keep the invariant query and one before/after query plan handy. Explain how you isolated the index effect from the smaller response payload.

[MySQL EXPLAIN ANALYZE](https://dev.mysql.com/doc/refman/8.0/en/explain.html) · [Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests)
