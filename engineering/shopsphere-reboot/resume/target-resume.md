# ShopSphere · Target résumé entries

[Roadmap](../README.md) · [Claim evidence and wording guide](claim-evidence.md)

These are future project entries for a one-page software engineering résumé. Bracketed fields are placeholders. Copy a bullet after its feature and benchmark or correctness test are complete. Use a shorter technology line when the bullets already name the supporting tools.

## Final version for backend and general SWE

**ShopSphere | E-Commerce Platform** · Java, Spring Boot, MySQL, Redis, Elasticsearch, RocketMQ

- Reduced Spring Boot product-detail P95 from [A] ms to [B] ms and MySQL reads per request by [D]% at [R] synthetic RPS using Redis cache-aside caching.
- Built Elasticsearch product search across [N] products with full-text ranking and category/price filters, improving held-out NDCG@10 from [Q0] to [Q1] over MySQL FULLTEXT.
- Designed a Stripe webhook inbox and RocketMQ order-event pipeline with a MySQL transactional outbox and idempotent workers, processing [U] unique events/sec and observing zero duplicate simulated confirmations in replay tests.

Use this version when the strongest evidence covers the catalog, search and event-processing work. Add a GitHub link to the project heading in the actual résumé. If the search result is inconclusive, use the search freshness or checkout reliability wording below.

## Final version for infrastructure and platform roles

**ShopSphere | E-Commerce Backend** · Java, Spring Boot, MySQL, Redis, RocketMQ, AWS, Docker

- Reduced product-detail P95 by [X]% at [R] synthetic RPS with Redis caching and used k6, Micrometer and Prometheus to measure tail latency and MySQL read load.
- Built a RocketMQ event pipeline with a MySQL transactional outbox and idempotent consumers, recovering from an injected worker outage in [T] seconds with zero duplicate simulated confirmations across [N] test events.
- Deployed ShopSphere to AWS EC2 with Docker and GitHub Actions OIDC, sustaining [G] successful requests/sec at [P] ms catalog P95 and [E]% unexpected failures in staging load tests.

If capacity was tested locally, use this final bullet instead.

- Automated AWS EC2 staging deployment with GitHub Actions OIDC, pinned Docker images, smoke checks, and a tested rollback.

## First-release version after Sprints 0–2

**ShopSphere | E-Commerce Backend** · Java, Spring Boot, MySQL, Redis, Stripe, React

- Reduced Spring Boot product-detail P95 by [X]% and MySQL reads per request by [D]% at [R] synthetic RPS using Redis cache-aside caching.
- Hardened Stripe checkout with exact-amount validation and verified MySQL stock reservation under contention, observing zero oversells across [N] synthetic checkout attempts.
- Built deterministic k6 workloads and a Micrometer/Prometheus dashboard to measure per-endpoint tail latency, SQL reads and connection-pool pressure across [C] seeded products.

If pagination produced a stronger result, replace the first bullet with the separate SQL result in [Sprint 1](../sprints/01-mysql-checkout.md).

## Swap-in bullets when the evidence fits

| Emphasis | Draft wording |
| --- | --- |
| Search freshness | Indexed [N] products in Elasticsearch and built a retryable MySQL-to-search sync, making catalog updates searchable within [L] seconds at P95. |
| Payment correctness | Hardened Stripe checkout with verified payment amounts and a durable webhook inbox, preserving accepted events across injected restarts and recording one paid transition per order across [N] replayed deliveries. |
| Inventory concurrency | Validated MySQL row-locked stock reservation with [N] synthetic checkout attempts against constrained inventory, observing zero oversells and reconciling reserved, released and sold units. |
| Flash-sale resilience | Implemented Redis-backed rate limiting across two Spring Boot replicas, sustaining [S]% checkout success during a [R]-RPS synthetic browse/search spike with [E]% unexpected failures. |
| Deployment only | Deployed a Dockerized Spring Boot and React staging stack to AWS EC2 and verified automated deployment, smoke checks and rollback through GitHub Actions. |

## What changed from the existing project entry

| Existing capability | Add this once verified |
| --- | --- |
| Product catalog backed by MySQL | Matched-load P95 and SQL-read results from the Redis comparison |
| Browsable products and categories | Ranked search with held-out relevance or measured index freshness |
| Stripe checkout and stock reservation | Verified inventory invariants plus durable, replay-tested payment events |
| Dockerized local application | A staging deployment with tested rollback or a cloud capacity result |

Two or three bullets are enough for the final entry. Choose one lead metric per bullet and keep detailed load settings, exclusions, and failure scenarios in the linked repository report.
