# Sprint 3 · Elasticsearch search

[Roadmap](../README.md) · [Previous sprint](02-redis-cache.md) · [Next sprint](04-payment-events.md)

**Time** · 2–3 weeks. **Depends on** · A stable paginated catalog. The payment work can proceed independently.

## What to learn

- Explain inverted indexes, analyzers, tokenization, BM25, structured filters and result ranking.
- Understand how MySQL writes become visible in Elasticsearch, why indexing can lag, and how to rebuild an index without losing concurrent changes.
- Calculate NDCG@10 from graded judgments and explain how evaluation query leakage can exaggerate the improvement.

## What to implement

- **Backend search.** Add a `ProductSearchService`, a product document mapping and a bounded `GET /product/search` endpoint. Start with keywords, category, price, deterministic sorting, and paginated results. Add facets or typo tolerance after core relevance is established. Use the same query and filter semantics in the React search UI and backend.
- **Search baseline.** Add a MySQL FULLTEXT baseline if it supports the chosen matching requirements. Save the SQL, sorting rules and index definitions. Freeze the baseline and Elasticsearch configurations before scoring the held-out set.
- **Index synchronization.** Add a small MySQL-backed indexing job written in the same transaction as admin catalog changes. Provide a bulk backfill and a retryable worker that upserts by product ID and guards against older jobs overwriting newer data. Test replay, restart and concurrent writes during rebuild. Keep stock availability and order decisions in MySQL.
- **Infrastructure and evaluation.** Add an Elasticsearch Compose profile with explicit resource limits. Save a representative set of product queries, including exact names, synonyms, typos, and broad category terms. Start with 100 queries, split into 60 for tuning and 40 held out. Grade a pooled set of results from both engines using one relevance rubric, with consistent judgment treatment for both engines.

## Target and evidence

| Target | How to prove it |
| --- | --- |
| 10,000 indexed products initially and 100,000 for a scale run | Compare indexed IDs and counts with the seeded source, verify changed products, and run an index rebuild. |
| Stretch target of +0.05 absolute NDCG@10 on held-out queries | Score both engines on the same query set and judgments. Publish the baseline and final values, query counts, and representative regressions. |
| Search P95 under 300 ms at an initial 40 offered search RPS | Use the same result size, query trace, filters and warmed state for both backends. Report achieved RPS and any resource differences. |
| P95 catalog-write-to-search visibility under 5 seconds in a healthy local run | Time from a committed change to the first search response that reflects it. Document the probe interval and report outage/replay behavior separately. |

## Implementation PRs

| PR | Scope | Merge gate |
| --- | --- | --- |
| 07 · `feat/product-search` | Search API, mapping, MySQL comparator, durable index jobs, backfill and Compose profile | Search returns the intended results and filters. Replayed jobs and a restart preserve catalog consistency. |
| 08 · `feat/search-ui-evaluation` | React search and filters, graded queries, relevance evaluation and latency comparison | The UI can search and paginate. The report includes held-out relevance, equivalent search load and indexing lag. |

## Résumé wording

**Relevance version.** “Built Elasticsearch product search with full-text ranking and category/price filters across [N] products, improving held-out NDCG@10 from [A] to [B] over MySQL FULLTEXT.”

**Freshness version.** “Indexed [N] ShopSphere products in Elasticsearch and built a retryable MySQL-to-search sync, making catalog updates searchable within [L] seconds at P95.”

Use the actual SQL baseline name and catalog count. Use “from [A] to [B]” for an interpretable improvement and document the query set separately. If relevance is similar, lead with the search functionality and the better-supported result, such as indexing freshness.

[Elasticsearch ranking evaluation](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/search-rank-eval)
